package mesosphere.marathon
package integration.setup

import java.io.File
import java.net.{ URLDecoder, URLEncoder }
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import akka.Done
import akka.actor.{ ActorSystem, Cancellable, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTestLike
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.integration.facades._
import mesosphere.marathon.raml.{ App, AppDockerVolume, Network, NetworkMode, AppHealthCheck, PodState, PodStatus, ReadMode }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.util.{ Lock, Retry }
import mesosphere.util.PortAllocator
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.exceptions.TestFailedDueToTimeoutException
import org.scalatest.time.{ Milliseconds, Span }
import org.scalatest.{ BeforeAndAfterAll, Suite }
import play.api.libs.json.{ JsObject, Json }

import scala.annotation.tailrec
import scala.async.Async.{ async, await }
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.sys.process.Process
import scala.util.Try

/**
  * Runs a marathon server for the given test suite
  * @param autoStart true if marathon should be started immediately
  * @param suiteName The test suite that owns this marathon
  * @param masterUrl The mesos master url
  * @param zkUrl The ZK url
  * @param conf any particular configuration
  * @param mainClass The main class
  */
case class LocalMarathon(
    autoStart: Boolean,
    suiteName: String,
    masterUrl: String,
    zkUrl: String,
    conf: Map[String, String] = Map.empty,
    mainClass: String = "mesosphere.marathon.Main")(implicit
  system: ActorSystem,
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler) extends AutoCloseable with StrictLogging {

  system.registerOnTermination(close())

  lazy val uuid = UUID.randomUUID.toString
  lazy val httpPort = PortAllocator.ephemeralPort()
  lazy val url = conf.get("https_port").fold(s"http://localhost:$httpPort")(httpsPort => s"https://localhost:$httpsPort")
  lazy val client = new MarathonFacade(url, PathId.empty)

  private val workDir = {
    val f = Files.createTempDirectory(s"marathon-$httpPort").toFile
    f.deleteOnExit()
    f
  }
  private def write(dir: File, fileName: String, content: String): String = {
    val file = File.createTempFile(fileName, "", dir)
    file.deleteOnExit()
    FileUtils.write(file, content)
    file.setReadable(true)
    file.getAbsolutePath
  }

  private val secretPath = write(workDir, fileName = "marathon-secret", content = "secret1")

  val config = Map(
    "master" -> masterUrl,
    "mesos_authentication_principal" -> "principal",
    "mesos_role" -> "foo",
    "http_port" -> httpPort.toString,
    "zk" -> zkUrl,
    "zk_timeout" -> 20.seconds.toMillis.toString,
    "zk_connection_timeout" -> 20.seconds.toMillis.toString,
    "zk_session_timeout" -> 20.seconds.toMillis.toString,
    "mesos_authentication_secret_file" -> s"$secretPath",
    "access_control_allow_origin" -> "*",
    "reconciliation_initial_delay" -> 5.minutes.toMillis.toString,
    "min_revive_offers_interval" -> "100",
    "hostname" -> "localhost",
    "logging_level" -> "debug",
    "minimum_viable_task_execution_duration" -> "0",
    "offer_matching_timeout" -> 10.seconds.toMillis.toString // see https://github.com/mesosphere/marathon/issues/4920
  ) ++ conf

  val args = config.flatMap {
    case (k, v) =>
      if (v.nonEmpty) {
        Seq(s"--$k", v)
      } else {
        Seq(s"--$k")
      }
  }(collection.breakOut)

  private var marathon = Option.empty[Process]

  if (autoStart) {
    start()
  }

  // it'd be great to be able to execute in memory, but we can't due to GuiceFilter using a static :(
  private lazy val processBuilder = {
    val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val cp = sys.props.getOrElse("java.class.path", "target/classes")
    val cmd = Seq(java, "-Xmx1024m", "-Xms256m", "-XX:+UseConcMarkSweepGC", "-XX:ConcGCThreads=2",
      // lower the memory pressure by limiting threads.
      "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
      "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
      "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
      "-Dscala.concurrent.context.minThreads=2",
      "-Dscala.concurrent.context.maxThreads=32",
      s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-classpath", cp, "-client", mainClass) ++ args
    Process(cmd, workDir, sys.env.toSeq: _*)
  }

  private def create(): Process = {
    processBuilder.run(ProcessOutputToLogStream(s"$suiteName-LocalMarathon-$httpPort"))
  }

  def start(): Future[Done] = {
    if (marathon.isEmpty) {
      marathon = Some(create())
    }
    val port = conf.get("http_port").orElse(conf.get("https_port")).map(_.toInt).getOrElse(httpPort)
    val future = Retry(s"marathon-$port", maxAttempts = Int.MaxValue, minDelay = 1.milli, maxDelay = 5.seconds, maxDuration = 5.minutes) {
      async {
        val result = await(Http().singleRequest(Get(s"http://localhost:$port/v2/leader")))
        result.discardEntityBytes() // forget about the body
        if (result.status.isSuccess()) { // linter:ignore //async/await
          Done
        } else {
          throw new Exception("Marathon not ready yet.")
        }
      }
    }
    future
  }

  private def activePids: Seq[String] = {
    val PIDRE = """^\s*(\d+)\s+(\S*)\s*(.*)$""".r
    Process("jps -lv").!!.split("\n").collect {
      case PIDRE(pid, main, jvmArgs) if main.contains(mainClass) && jvmArgs.contains(uuid) => pid
    }(collection.breakOut)
  }

  def isRunning(): Boolean =
    activePids.nonEmpty

  def exitValue(): Option[Int] = marathon.map(_.exitValue())

  def stop(): Unit = {
    marathon.foreach(_.destroy())
    marathon = Option.empty[Process]

    val pids = activePids
    if (pids.nonEmpty) {
      Process(s"kill -9 ${pids.mkString(" ")}").!
    }
  }

  def restart(): Future[Done] = {
    logger.info(s"Restarting Marathon on $httpPort")
    stop()
    start().map { x =>
      logger.info(s"Restarted Marathon on $httpPort")
      x
    }
  }

  override def close(): Unit = {
    stop()
    Try(FileUtils.deleteDirectory(workDir))
  }
}

trait HealthCheckEndpoint extends StrictLogging with ScalaFutures {

  protected val healthChecks = Lock(mutable.ListBuffer.empty[IntegrationHealthCheck])
  val registeredReadinessChecks = Lock(mutable.ListBuffer.empty[IntegrationReadinessCheck])

  implicit val system: ActorSystem
  implicit val mat: Materializer

  /**
    * Note! This is declared as lazy in order to prevent eager evaluation of values on which it depends
    * We initialize it during the before hook and wait for Marathon to respond.
    */
  protected[setup] lazy val healthEndpoint = {
    val route = {
      import akka.http.scaladsl.server.Directives._
      val mapper = new ObjectMapper() with ScalaObjectMapper
      mapper.registerModule(DefaultScalaModule)

      implicit val unmarshal = new FromRequestUnmarshaller[Map[String, Any]] {
        override def apply(value: HttpRequest)(implicit ec: ExecutionContext, materializer: Materializer): Future[Map[String, Any]] = {
          value.entity.toStrict(patienceConfig.timeout)(materializer).map { entity =>
            mapper.readValue[Map[String, Any]](entity.data.utf8String)
          }(ec)
        }
      }

      get {
        path(Segment / Segment / "health") { (uriEncodedAppId, versionId) =>
          import PathId._
          val appId = URLDecoder.decode(uriEncodedAppId, "UTF-8").toRootPath

          def instance = healthChecks(_.find { c => c.appId == appId && c.versionId == versionId })

          val state = instance.fold(true)(_.healthy)

          logger.info(s"Received health check request: app=$appId, version=$versionId reply=$state")
          if (state) {
            complete(HttpResponse(status = StatusCodes.OK))
          } else {
            complete(HttpResponse(status = StatusCodes.InternalServerError))
          }
        } ~ path(Segment / Segment / Segment / "ready") { (uriEncodedAppId, versionId, taskId) =>
          import PathId._
          val appId = URLDecoder.decode(uriEncodedAppId, "UTF-8").toRootPath

          // Find a fitting registred readiness check. If the check has no task id set we ignore it.
          def check: Option[IntegrationReadinessCheck] = registeredReadinessChecks(_.find { c =>
            c.appId == appId && c.versionId == versionId && c.taskId.fold(true)(_ == taskId)
          })

          // An app is not ready by default to avoid race conditions.
          val isReady = check.fold(false)(_.call)

          logger.info(s"Received readiness check request: app=$appId, version=$versionId taskId=$taskId reply=$isReady")

          if (isReady) {
            complete(HttpResponse(status = StatusCodes.OK))
          } else {
            complete(HttpResponse(status = StatusCodes.InternalServerError))
          }
        } ~ path(Remaining) { path =>
          require(false, s"$path was unmatched!")
          complete(HttpResponse(status = StatusCodes.InternalServerError))
        }
      }
    }
    val port = PortAllocator.ephemeralPort()
    val server = Http().bindAndHandle(route, "0.0.0.0", port).futureValue
    logger.info(s"Listening for health events on $port")
    server
  }

  /**
    * Add an integration health check to internal health checks. The integration health check is used to control the
    * health check replies for our app mock.
    *
    * @param appId The app id of the app mock
    * @param versionId The version of the app mock
    * @param state The initial health status of the app mock
    * @return The IntegrationHealthCheck object which is used to control the replies.
    */
  def registerAppProxyHealthCheck(appId: PathId, versionId: String, state: Boolean): IntegrationHealthCheck = {
    val check = new IntegrationHealthCheck(appId, versionId, state)
    healthChecks { checks =>
      checks.filter(c => c.appId == appId && c.versionId == versionId).foreach(checks -= _)
      checks += check
    }
    check
  }

  /**
    * Adds an integration readiness check to internal readiness checks. The behaviour is similar to integration health
    * checks.
    *
    * @param appId The app id of the app mock
    * @param versionId The version of the app mock
    * @param taskId Optional task id to identify the task of the app mock.
    * @return The IntegrationReadinessCheck object which is used to control replies.
    */
  def registerProxyReadinessCheck(appId: PathId, versionId: String, taskId: Option[String] = None): IntegrationReadinessCheck = {
    val check = new IntegrationReadinessCheck(appId, versionId, taskId)
    registeredReadinessChecks { checks =>
      checks.filter(c => c.appId == appId && c.versionId == versionId && c.taskId == taskId).foreach(checks -= _)
      checks += check
    }
    check
  }
}

/**
  * Base trait for tests that need a marathon
  */
trait MarathonTest extends HealthCheckEndpoint with StrictLogging with ScalaFutures with Eventually {
  def marathonUrl: String
  def marathon: MarathonFacade
  def leadingMarathon: Future[LocalMarathon]
  def mesos: MesosFacade
  val testBasePath: PathId
  def suiteName: String

  val appProxyIds = Lock(mutable.ListBuffer.empty[String])

  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  implicit val scheduler: Scheduler

  case class CallbackEvent(eventType: String, info: Map[String, Any])
  object CallbackEvent {
    def apply(event: ITEvent): CallbackEvent = CallbackEvent(event.eventType, event.info)
  }

  implicit class CallbackEventToStatusUpdateEvent(val event: CallbackEvent) {
    def taskStatus: String = event.info.get("taskStatus").map(_.toString).getOrElse("")
    def message: String = event.info("message").toString
    def id: String = event.info("id").toString
    def running: Boolean = taskStatus == "TASK_RUNNING"
    def finished: Boolean = taskStatus == "TASK_FINISHED"
    def failed: Boolean = taskStatus == "TASK_FAILED"
  }

  object StatusUpdateEvent {
    def unapply(event: CallbackEvent): Option[CallbackEvent] = {
      if (event.eventType == "status_update_event") Some(event)
      else None
    }
  }

  protected val events = new ConcurrentLinkedQueue[ITSSEEvent]()

  protected[setup] def killAppProxies(): Unit = {
    val PIDRE = """^\s*(\d+)\s+(.*)$""".r
    val allJavaIds = Process("jps -lv").!!.split("\n")
    val pids = allJavaIds.collect {
      case PIDRE(pid, exec) if appProxyIds(_.exists(exec.contains)) => pid
    }
    if (pids.nonEmpty) {
      Process(s"kill -9 ${pids.mkString(" ")}").run().exitValue()
    }
  }
  implicit class PathIdTestHelper(path: String) {
    def toRootTestPath: PathId = testBasePath.append(path).canonicalPath()
    def toTestPath: PathId = testBasePath.append(path)
  }

  /**
    * Constructs the proper health proxy endpoint argument for the Python app mock.
    *
    * @param appId The app id whose health is checked
    * @param versionId The version of the app
    * @return URL to health check endpoint
    */
  def healthEndpointFor(appId: PathId, versionId: String): String = {
    val encodedAppId = URLEncoder.encode(appId.toString, "UTF-8")
    s"http://$$HOST:${healthEndpoint.localAddress.getPort}/$encodedAppId/$versionId"
  }

  def appProxyHealthCheck(
    gracePeriod: FiniteDuration = 1.seconds,
    interval: FiniteDuration = 1.second,
    maxConsecutiveFailures: Int = Int.MaxValue,
    portIndex: Option[Int] = Some(0)): AppHealthCheck =
    raml.AppHealthCheck(
      gracePeriodSeconds = gracePeriod.toSeconds.toInt,
      intervalSeconds = interval.toSeconds.toInt,
      maxConsecutiveFailures = maxConsecutiveFailures,
      portIndex = portIndex,
      protocol = raml.AppHealthCheckProtocol.Http
    )

  def appProxy(appId: PathId, versionId: String, instances: Int,
    healthCheck: Option[raml.AppHealthCheck] = Some(appProxyHealthCheck()),
    dependencies: Set[PathId] = Set.empty): App = {

    val projectDir = sys.props.getOrElse("user.dir", ".")
    val appMock: File = new File(projectDir, "src/test/python/app_mock.py")
    val cmd = Some(s"""echo APP PROXY $$MESOS_TASK_ID RUNNING; ${appMock.getAbsolutePath} """ +
      s"""$$PORT0 $appId $versionId ${healthEndpointFor(appId, versionId)}""")

    App(
      id = appId.toString,
      cmd = cmd,
      executor = "//cmd",
      instances = instances,
      cpus = 0.01, mem = 32.0,
      healthChecks = healthCheck.toSet,
      dependencies = dependencies.map(_.toString)
    )
  }

  def dockerAppProxy(appId: PathId, versionId: String, instances: Int, healthCheck: Option[AppHealthCheck] = Some(appProxyHealthCheck()), dependencies: Set[PathId] = Set.empty): App = {
    val projectDir = sys.props.getOrElse("user.dir", ".")
    val containerDir = "/opt/marathon"

    val encodedAppId = URLEncoder.encode(appId.toString, "UTF-8")
    val cmd = Some("""echo APP PROXY $$MESOS_TASK_ID RUNNING; /opt/marathon/python/app_mock.py """ +
      s"""$$PORT0 $appId $versionId ${healthEndpointFor(appId, versionId)}""")

    App(
      id = appId.toString,
      cmd = cmd,
      container = Some(raml.Container(
        `type` = raml.EngineType.Docker,
        docker = Some(raml.DockerContainer(
          image = "python:3.4.6-alpine"
        )),
        volumes = collection.immutable.Seq(
          new AppDockerVolume(hostPath = s"$projectDir/src/test/python", containerPath = s"$containerDir/python", mode = ReadMode.Ro)
        )
      )),
      instances = instances,
      cpus = 0.5,
      mem = 128,
      healthChecks = healthCheck.toSet,
      dependencies = dependencies.map(_.toString),
      networks = Seq(Network(mode = NetworkMode.Host))
    )
  }

  def waitForTasks(appId: PathId, num: Int, maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis)(implicit facade: MarathonFacade = marathon): List[ITEnrichedTask] = {
    eventually(timeout(Span(maxWait.toMillis, Milliseconds))) {
      val tasks = Try(facade.tasks(appId)).map(_.value).getOrElse(Nil).filter(_.launched)
      logger.info(s"${tasks.size}/$num tasks launched for $appId")
      require(tasks.size == num, s"Waiting for $num tasks to be launched")
      tasks
    }
  }

  // We shouldn't eat exceptions in clenaUp() methods: it's a source of hard to find bugs if
  // we just move on to the next test, that expects a "clean state". We should fail loud and
  // proud here and find out why the clean-up fails.
  def cleanUp(): Unit = {
    logger.info(">>> Starting to CLEAN UP...")
    events.clear()

    // Wait for a clean slate in Marathon, if there is a running deployment or a runSpec exists
    logger.info("Clean Marathon State")
    //do not fail here, since the require statements will ensure a correct setup and fail otherwise
    Try(waitForDeployment(eventually(marathon.deleteGroup(testBasePath, force = true))))

    WaitTestSupport.waitUntil("clean slate in Mesos", patienceConfig.timeout.toMillis.millis) {
      val occupiedAgents = mesos.state.value.agents.filter { agent => agent.usedResources.nonEmpty || agent.reservedResourcesByRole.nonEmpty }
      occupiedAgents.foreach { agent =>
        import mesosphere.marathon.integration.facades.MesosFormats._
        val usedResources: String = Json.prettyPrint(Json.toJson(agent.usedResources))
        val reservedResources: String = Json.prettyPrint(Json.toJson(agent.reservedResourcesByRole))
        logger.info(s"""Waiting for blank slate Mesos...\n "used_resources": "$usedResources"\n"reserved_resources": "$reservedResources"""")
      }
      occupiedAgents.isEmpty
    }

    val apps = marathon.listAppsInBaseGroup
    require(apps.value.isEmpty, s"apps weren't empty: ${apps.entityPrettyJsonString}")
    val pods = marathon.listPodsInBaseGroup
    require(pods.value.isEmpty, s"pods weren't empty: ${pods.entityPrettyJsonString}")
    val groups = marathon.listGroupsInBaseGroup
    require(groups.value.isEmpty, s"groups weren't empty: ${groups.entityPrettyJsonString}")
    events.clear()
    healthChecks(_.clear())
    killAppProxies()

    logger.info("... CLEAN UP finished <<<")
  }

  def waitForHealthCheck(check: IntegrationHealthCheck, maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis) = {
    WaitTestSupport.waitUntil("Health check to get queried", maxWait) { check.pinged.get }
  }

  def waitForDeploymentId(deploymentId: String, maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis): CallbackEvent = {
    waitForEventWith("deployment_success", _.id == deploymentId, maxWait)
  }

  def waitForStatusUpdates(kinds: String*) = kinds.foreach { kind =>
    logger.info(s"Wait for status update event with kind: $kind")
    waitForEventWith("status_update_event", _.taskStatus == kind)
  }

  def waitForEvent(
    kind: String,
    maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis): CallbackEvent =
    waitForEventWith(kind, _ => true, maxWait)

  def waitForEventWith(
    kind: String,
    fn: CallbackEvent => Boolean, maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis): CallbackEvent = {
    waitForEventMatching(s"event $kind to arrive", maxWait) { event =>
      event.eventType == kind && fn(event)
    }
  }

  /**
    * Consumes the next event from the events queue within deadline. Does not throw. Returns None if unable to return an
    * event by that time.
    *
    * @param deadline The time after which to stop attempting to get an event and return None
    */
  private def nextEvent(deadline: Deadline): Option[ITSSEEvent] = try {
    eventually(timeout(Span(deadline.timeLeft.toMillis, Milliseconds))) {
      val r = Option(events.poll)
      if (r.isEmpty)
        throw new NoSuchElementException
      r
    }
  } catch {
    case _: NoSuchElementException =>
      None
    case _: TestFailedDueToTimeoutException =>
      None
  }

  /**
    * Method waits for events and calls their callbacks independently of the events order. It receives a
    * mutable map of EventId -> Callback e.g. mutable.Map("deployment_failed" -> _.id == deploymentId),
    * checks every event for it's existence in the map and if found, calls it's callback method. If successful, the entry
    * is removed from the map. Returns if the map is empty.
    */
  def waitForEventsWith(
    description: String,
    waitingFor: mutable.Map[String, CallbackEvent => Boolean],
    maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis) = {
    waitForEventMatching(description) { event =>
      if (waitingFor.get(event.eventType).fold(false)(fn => fn(event))) {
        waitingFor -= event.eventType
      }
      waitingFor.isEmpty
    }
  }

  def waitForEventMatching(
    description: String,
    maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis)(fn: CallbackEvent => Boolean): CallbackEvent = {
    val deadline = maxWait.fromNow
    @tailrec
    def iter(): CallbackEvent = {
      nextEvent(deadline) match {
        case Some(ITConnected) =>
          throw new MarathonTest.UnexpectedConnect
        case Some(event: ITEvent) =>
          val cbEvent = CallbackEvent(event)
          if (fn(cbEvent)) {
            cbEvent
          } else {
            logger.info(s"Event $event did not match criteria skipping to next event")
            iter()
          }
        case None =>
          throw new RuntimeException(s"No events matched <$description>")
      }
    }
    iter()
  }

  /**
    * Blocks until a single connected event is consumed. Discards any events up to that point.
    *
    * Not reasoning about SSE connection state will lead to flaky tests. If a master is killed, you should wait for the
    * SSE stream to reconnect before doing anything else, or you could miss events.
    */
  def waitForSSEConnect(maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis): Unit = {
    @tailrec
    val deadline = maxWait.fromNow
    def iter(): Unit = {
      nextEvent(deadline) match {
        case Some(event: ITEvent) =>
          logger.info(s"Event ${event} was not a connected event; skipping")
          iter()
        case Some(ITConnected) =>
          logger.info("ITConnected event consumed")
        case None =>
          throw new RuntimeException("No connected events")
      }
    }
    iter()
  }

  /**
    * Wait for the events of the given kinds (=types).
    */
  def waitForEvents(kinds: String*)(maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis): Map[String, Seq[CallbackEvent]] = {

    val deadline = maxWait.fromNow

    /** Receive the events for the given kinds (duplicates allowed) in any order. */
    val receivedEventsForKinds: Seq[CallbackEvent] = {
      var eventsToWaitFor = kinds
      val receivedEvents = Vector.newBuilder[CallbackEvent]

      while (eventsToWaitFor.nonEmpty) {
        val event = waitForEventMatching(s"event $eventsToWaitFor to arrive", deadline.timeLeft) { event =>
          eventsToWaitFor.contains(event.eventType)
        }
        receivedEvents += event

        // Remove received event kind. Only remove one element for duplicates.
        val kindIndex = eventsToWaitFor.indexWhere(_ == event.eventType)
        assert(kindIndex >= 0)
        eventsToWaitFor = eventsToWaitFor.patch(kindIndex, Nil, 1)
      }

      receivedEvents.result()
    }

    receivedEventsForKinds.groupBy(_.eventType)
  }

  def waitForDeployment(change: RestResult[_], maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis): CallbackEvent = {
    require(change.success, s"Deployment request has not been successful. httpCode=${change.code} body=${change.entityString}")
    val deploymentId = change.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).getOrElse(throw new IllegalArgumentException("No deployment id found in Http Header"))
    waitForDeploymentId(deploymentId.value, maxWait)
  }

  def waitForPod(podId: PathId): PodStatus = {
    eventually {
      Try(marathon.status(podId)).map(_.value).toOption.filter(_.status == PodState.Stable).get
    }
  }

  protected[setup] def teardown(): Unit = {
    Try {
      val frameworkId = marathon.info.entityJson.as[JsObject].value("frameworkId").as[String]
      mesos.teardown(frameworkId).futureValue
    }
    Try(healthEndpoint.unbind().futureValue)
    Try(killAppProxies())
  }

  /**
    * Connects repeatedly to the Marathon SSE endpoint until cancelled.
    * Yields each event in order.
    */
  def startEventSubscriber(): Cancellable = {
    @volatile var cancelled = false
    def iter(): Unit = {
      import akka.stream.scaladsl.Source
      logger.info("SSEStream: Connecting")
      Source.fromFuture(leadingMarathon)
        .mapAsync(1) { leader =>
          async {
            logger.info(s"SSEStream: Acquiring connection to ${leader.url}")
            val stream = await(leader.client.events())
            logger.info(s"SSEStream: Connection acquired to ${leader.url}")

            /* A potentially impossible edge case exists in which we query the leader, and then before we get a connection
             * to that instance, it restarts and is no longer a leader.
             *
             * By checking the leader again once obtaining a connection to the SSE event stream, we have conclusive proof
             * that we are consuming from the current leader, and we keep our connected events as deterministic as
             * possible. */
            val leaderAfterConnection = await(leadingMarathon)
            logger.info(s"SSEStream: ${leader.url} is the leader")
            if (leader != leaderAfterConnection) {
              stream.runWith(Sink.cancelled)
              throw new RuntimeException("Leader status changed since first connecting to stream")
            } else {
              stream
            }
          }
        }
        .flatMapConcat { stream =>
          // We prepend the ITConnected event here in order to avoid emitting an ITConnected event on failed connections
          stream.prepend(Source.single(ITConnected))
        }
        .runForeach { e: ITSSEEvent =>
          e match {
            case ITConnected =>
              logger.info(s"SSEStream: Connected")
            case event: ITEvent =>
              logger.info(s"SSEStream: Received callback event: ${event.eventType} with props ${event.info}")
          }
          events.offer(e)
        }
        .onComplete {
          case result =>
            if (!cancelled) {
              logger.info(s"SSEStream: Leader event stream was closed reason: ${result}")
              logger.info("Reconnecting")
              scheduler.scheduleOnce(patienceConfig.interval) { iter() }
            }
        }
    }
    iter()
    new Cancellable {
      override def cancel(): Boolean = {
        cancelled = true
        true
      }
      override def isCancelled: Boolean = cancelled
    }
  }
}

object MarathonTest extends StrictLogging {
  class UnexpectedConnect extends Exception("Received an unexpected SSE event stream Connection event. This is " +
    "considered an exception because not thinking about re-connection events properly can lead to race conditions in " +
    "the tests. You should call waitForSSEConnect() after killing a Marathon leader to ensure no events are dropped.")
}

/**
  * Fixture that can be used for a single test case.
  */
trait MarathonFixture extends AkkaUnitTestLike with MesosClusterTest with ZookeeperServerTest {
  def withMarathon[T](suiteName: String, marathonArgs: Map[String, String] = Map.empty)(f: (LocalMarathon, MarathonTest) => T): T = {
    val marathonServer = LocalMarathon(autoStart = false, suiteName = suiteName, masterUrl = mesosMasterUrl,
      zkUrl = s"zk://${zkServer.connectUri}/marathon-$suiteName", conf = marathonArgs)
    marathonServer.start().futureValue

    val marathonTest = new MarathonTest {
      override def marathonUrl: String = s"http://localhost:${marathonServer.httpPort}"
      override def marathon: MarathonFacade = marathonServer.client
      override def mesos: MesosFacade = MarathonFixture.this.mesos
      override val testBasePath: PathId = PathId("/")
      override implicit val system: ActorSystem = MarathonFixture.this.system
      override implicit val mat: Materializer = MarathonFixture.this.mat
      override implicit val ctx: ExecutionContext = MarathonFixture.this.ctx
      override implicit val scheduler: Scheduler = MarathonFixture.this.scheduler
      override val suiteName: String = MarathonFixture.this.suiteName
      override implicit def patienceConfig: PatienceConfig = PatienceConfig(MarathonFixture.this.patienceConfig.timeout, MarathonFixture.this.patienceConfig.interval)
      override def leadingMarathon = Future.successful(marathonServer)
    }
    val sseStream = marathonTest.startEventSubscriber()
    try {
      marathonTest.healthEndpoint
      marathonTest.waitForSSEConnect()
      f(marathonServer, marathonTest)
    } finally {
      sseStream.cancel()
      marathonTest.teardown()
      marathonServer.stop()
    }
  }
}

object MarathonFixture extends MarathonFixture

/**
  * base trait that spins up/tears down a marathon and has all of the original tooling from
  * SingleMarathonIntegrationTest.
  */
trait MarathonSuite extends Suite with StrictLogging with ScalaFutures with BeforeAndAfterAll with Eventually with MarathonTest {
  abstract override def afterAll(): Unit = {
    teardown()
    super.afterAll()
  }
}

/**
  * Base trait that starts a local marathon but doesn't have mesos/zookeeper yet
  */
trait LocalMarathonTest extends MarathonTest with ScalaFutures
    with AkkaUnitTestLike with MesosTest with ZookeeperServerTest {

  def marathonArgs: Map[String, String] = Map.empty

  lazy val marathonServer = LocalMarathon(autoStart = false, suiteName = suiteName, masterUrl = mesosMasterUrl,
    zkUrl = s"zk://${zkServer.connectUri}/marathon",
    conf = marathonArgs)
  lazy val marathonUrl = s"http://localhost:${marathonServer.httpPort}"

  val testBasePath: PathId = PathId("/")
  lazy val marathon = marathonServer.client
  lazy val appMock: AppMockFacade = new AppMockFacade()

  /**
    * Return the current leading Marathon
    * Expected to retry for a significant period of time until succeeds
    */
  override def leadingMarathon: Future[LocalMarathon] =
    Future.successful(marathonServer)

  @volatile private var sseStream: Option[Cancellable] = None

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    marathonServer.start().futureValue
    sseStream = Some(startEventSubscriber())
    waitForSSEConnect()
  }

  abstract override def afterAll(): Unit = {
    sseStream.foreach(_.cancel)
    teardown()
    Try(marathonServer.close())
    super.afterAll()
  }
}

/**
  * trait that has marathon, zk, and a mesos ready to go
  */
trait EmbeddedMarathonTest extends Suite with StrictLogging with ZookeeperServerTest with MesosClusterTest with LocalMarathonTest {
  // disable failover timeout to assist with cleanup ops; terminated marathons are immediately removed from mesos's
  // list of frameworks
  override def marathonArgs: Map[String, String] = Map("failover_timeout" -> "0")
}

/**
  * Trait that has a Marathon cluster, zk, and Mesos via mesos-local ready to go.
  *
  * It provides multiple Marathon instances. This allows e.g. leadership rotation.
  */
trait MarathonClusterTest extends Suite with StrictLogging with ZookeeperServerTest with MesosClusterTest with LocalMarathonTest {
  val numAdditionalMarathons = 2
  lazy val additionalMarathons = 0.until(numAdditionalMarathons).map { _ =>
    LocalMarathon(autoStart = false, suiteName = suiteName, masterUrl = mesosMasterUrl,
      zkUrl = s"zk://${zkServer.connectUri}/marathon",
      conf = marathonArgs)
  }
  lazy val marathonFacades = marathon +: additionalMarathons.map(_.client)
  lazy val allMarathonServers = marathonServer +: additionalMarathons

  override def leadingMarathon: Future[LocalMarathon] = {
    val leader = Retry("querying leader", maxAttempts = 50, maxDelay = 1.second, maxDuration = patienceConfig.timeout) {
      Future.firstCompletedOf(marathonFacades.map(_.leaderAsync()))
    }
    leader.map { leader =>
      allMarathonServers.find { _.httpPort == leader.value.port }.head
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    Future.sequence(additionalMarathons.map(_.start())).futureValue
  }

  override def afterAll(): Unit = {
    Try(additionalMarathons.foreach(_.close()))
    super.afterAll()
  }

  def nonLeader(leader: ITLeaderResult): MarathonFacade = {
    marathonFacades.find(!_.url.contains(leader.port.toString)).get
  }

  override def cleanUp(): Unit = {
    Future.sequence(marathonServer.start() +: additionalMarathons.map(_.start())).futureValue
    super.cleanUp()
  }
}

