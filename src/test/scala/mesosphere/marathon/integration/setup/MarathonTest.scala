package mesosphere.marathon
package integration.setup

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import akka.Done
import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import akka.stream.Materializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.core.health.{ HealthCheck, MarathonHealthCheck, MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.integration.facades.{ ITEnrichedTask, ITLeaderResult, MarathonFacade, MesosFacade }
import mesosphere.marathon.raml.{ PodState, PodStatus, Resources }
import mesosphere.marathon.state.{ AppDefinition, Container, DockerVolume, PathId }
import mesosphere.marathon.test.ExitDisabledTest
import mesosphere.marathon.util.{ Lock, Retry }
import mesosphere.util.PortAllocator
import org.apache.commons.io.FileUtils
import org.apache.mesos.Protos
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.{ Milliseconds, Span }
import org.scalatest.{ BeforeAndAfterAll, Suite }
import play.api.libs.json.{ JsString, Json }

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
    scheduler: Scheduler) extends AutoCloseable {

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
    "zk_session_timeout" -> 20.seconds.toMillis.toString,
    "mesos_authentication_secret_file" -> s"$secretPath",
    "event_subscriber" -> "http_callback",
    "access_control_allow_origin" -> "*",
    "reconciliation_initial_delay" -> 5.minutes.toMillis.toString,
    "min_revive_offers_interval" -> "100",
    "hostname" -> "localhost",
    "logging_level" -> "debug",
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
    val memSettings = s"-Xmx${Runtime.getRuntime.maxMemory()}"
    val cmd = Seq(java, memSettings, s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-classpath", cp, mainClass) ++ args
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
    val future = Retry(s"marathon-$port", maxAttempts = Int.MaxValue, minDelay = 1.milli, maxDelay = 5.seconds, maxDuration = 90.seconds) {
      async {
        val result = await(Http(system).singleRequest(Get(s"http://localhost:$port/v2/leader")))
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

  override def close(): Unit = {
    stop()
    Try(FileUtils.deleteDirectory(workDir))
  }
}

/**
  * base trait that spins up/tears down a marathon and has all of the original tooling from
  * SingleMarathonIntegrationTest.
  */
trait MarathonTest extends Suite with StrictLogging with ScalaFutures with BeforeAndAfterAll with Eventually {
  def marathonUrl: String
  def marathon: MarathonFacade
  def mesos: MesosFacade
  val testBasePath: PathId

  protected val appProxyIds = Lock(mutable.ListBuffer.empty[String])

  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  implicit val scheduler: Scheduler

  system.registerOnTermination(killAppProxies())

  case class CallbackEvent(eventType: String, info: Map[String, Any])

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

  protected val events = new ConcurrentLinkedQueue[CallbackEvent]()
  protected val healthChecks = Lock(mutable.ListBuffer.empty[IntegrationHealthCheck])

  /**
    * Note! This is declared as lazy in order to prevent eager evaluation of values on which it depends
    * We initialize it during the before hook and wait for Marathon to respond.
    */
  protected[setup] lazy val callbackEndpoint = {
    val route = {
      import akka.http.scaladsl.server.Directives._
      val mapper = new ObjectMapper() with ScalaObjectMapper
      mapper.registerModule(DefaultScalaModule)

      implicit val unmarshal = new FromRequestUnmarshaller[Map[String, Any]] {
        override def apply(value: HttpRequest)(implicit ec: ExecutionContext, materializer: Materializer): Future[Map[String, Any]] = {
          value.entity.toStrict(5.seconds)(materializer).map { entity =>
            mapper.readValue[Map[String, Any]](entity.data.utf8String)
          }(ec)
        }
      }

      (post & entity(as[Map[String, Any]])) { event =>
        val kind = event.get("eventType") match {
          case Some(JsString(s)) => s
          case Some(s) => s.toString
          case None => "unknown"
        }
        logger.info(s"Received callback event: $kind with props $event")
        events.add(CallbackEvent(kind, event))
        complete(HttpResponse(status = StatusCodes.OK))
      } ~ get {
        path("health" / Segments) { uriPath =>
          import PathId._
          val (path, remaining) = uriPath.splitAt(uriPath.size - 2)
          val (versionId, port) = (remaining.head, remaining.tail.head.toInt)
          val appId = path.mkString("/").toRootPath
          def instance = healthChecks(_.find { c => c.appId == appId && c.versionId == versionId && c.port == port })
          def definition = healthChecks(_.find { c => c.appId == appId && c.versionId == versionId && c.port == 0 })
          val state = instance.orElse(definition).fold(true)(_.healthy)
          if (state) {
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
    val server = Http().bindAndHandle(route, "localhost", port).futureValue
    marathon.subscribe(s"http://localhost:$port")
    logger.info(s"Listening for events on $port")
    server
  }

  abstract override def afterAll(): Unit = {
    Try(marathon.unsubscribe(s"http://localhost:${callbackEndpoint.localAddress.getPort}"))
    Try(callbackEndpoint.unbind().futureValue)
    Try(killAppProxies())
    super.afterAll()
  }

  private def killAppProxies(): Unit = {
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

  def appProxyHealthCheck(
    gracePeriod: FiniteDuration = 3.seconds,
    interval: FiniteDuration = 1.second,
    maxConsecutiveFailures: Int = Int.MaxValue,
    portIndex: Option[PortReference] = Some(PortReference.ByIndex(0))): MarathonHealthCheck =
    MarathonHttpHealthCheck(gracePeriod = gracePeriod, interval = interval, maxConsecutiveFailures = maxConsecutiveFailures, portIndex = portIndex)

  def appProxy(appId: PathId, versionId: String, instances: Int, healthCheck: Option[HealthCheck] = Some(appProxyHealthCheck()), dependencies: Set[PathId] = Set.empty): AppDefinition = {

    val projectDir = sys.props.getOrElse("user.dir", ".")
    val appMock: File = new File(projectDir, "src/test/python/app_mock.py")
    val cmd = Some(s"""echo APP PROXY $$MESOS_TASK_ID RUNNING; ${appMock.getAbsolutePath} """ +
      s"""$$PORT0 $appId $versionId http://127.0.0.1:${callbackEndpoint.localAddress.getPort}/health$appId/$versionId""")
    AppDefinition(
      id = appId,
      cmd = cmd,
      executor = "//cmd",
      instances = instances,
      resources = Resources(cpus = 0.5, mem = 128.0),
      healthChecks = healthCheck.toSet,
      dependencies = dependencies
    )
  }

  def dockerAppProxy(appId: PathId, versionId: String, instances: Int, healthCheck: Option[HealthCheck] = Some(appProxyHealthCheck()), dependencies: Set[PathId] = Set.empty): AppDefinition = {
    val projectDir = sys.props.getOrElse("user.dir", ".")
    val containerDir = "/opt/marathon"

    val cmd = Some(s"""echo APP PROXY $$MESOS_TASK_ID RUNNING; /opt/marathon/python/app_mock.py """ +
      s"""$$PORT0 $appId $versionId http://127.0.0.1:${callbackEndpoint.localAddress.getPort}/health$appId/$versionId""")

    AppDefinition(
      id = appId,
      cmd = cmd,
      container = Some(Container.Docker(
        image = "python:3.4.6-alpine",
        network = Some(Protos.ContainerInfo.DockerInfo.Network.HOST),
        volumes = collection.immutable.Seq(
          new DockerVolume(hostPath = s"$projectDir/src/test/python", containerPath = s"$containerDir/python", mode = Protos.Volume.Mode.RO)
        )
      )),
      instances = instances,
      resources = Resources(cpus = 0.5, mem = 128.0),
      healthChecks = healthCheck.toSet,
      dependencies = dependencies
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

  def cleanUp(withSubscribers: Boolean = false): Unit = {
    logger.info("Starting to CLEAN UP !!!!!!!!!!")

    try {
      events.clear()

      // Wait for a clean slate in Marathon, if there is a running deployment or a runSpec exists
      logger.info("Clean Marathon State")
      lazy val group = marathon.group(testBasePath).value
      lazy val deployments = marathon.listDeploymentsForBaseGroup().value
      if (deployments.nonEmpty || group.transitiveRunSpecs.nonEmpty || group.transitiveGroupsById.nonEmpty) {
        //do not fail here, since the require statements will ensure a correct setup and fail otherwise
        Try(waitForDeployment(eventually(marathon.deleteGroup(testBasePath, force = true))))
      }

      WaitTestSupport.waitUntil("clean slate in Mesos", patienceConfig.timeout.toMillis.millis) {
        val occupiedAgents = mesos.state.value.agents.filter { agent => !agent.usedResources.isEmpty && agent.reservedResourcesByRole.nonEmpty }
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
      val groups = marathon.listGroupsInBaseGroup
      require(groups.value.isEmpty, s"groups weren't empty: ${groups.entityPrettyJsonString}")
      events.clear()
      healthChecks(_.clear())
      killAppProxies()
      if (withSubscribers) marathon.listSubscribers.value.urls.foreach(marathon.unsubscribe)
    } catch {
      case e: Throwable => logger.error("Clean up failed with", e)
    }

    logger.info("CLEAN UP finished !!!!!!!!!")
  }

  def appProxyCheck(appId: PathId, versionId: String, state: Boolean): IntegrationHealthCheck = {
    val check = new IntegrationHealthCheck(appId, versionId, 0, state)
    healthChecks { checks =>
      checks.filter(c => c.appId == appId && c.versionId == versionId).foreach(checks -= _)
      checks += check
    }
    check
  }

  def waitForHealthCheck(check: IntegrationHealthCheck, maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis) = {
    WaitTestSupport.waitUntil("Health check to get queried", maxWait) { check.pinged }
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

  def waitForEventMatching(
    description: String,
    maxWait: FiniteDuration = patienceConfig.timeout.toMillis.millis)(fn: CallbackEvent => Boolean): CallbackEvent = {
    @tailrec
    def matchingEvent: Option[CallbackEvent] = if (events.isEmpty) None else {
      val event = events.poll()
      if (fn(event)) {
        Some(event)
      } else {
        logger.info(s"Event $event did not match criteria skipping to next event")
        matchingEvent
      }
    }

    eventually(timeout(Span(maxWait.toMillis, Milliseconds))) {
      require(!events.isEmpty, s"No events matched <$description>")
      matchingEvent.getOrElse(throw new RuntimeException("No matching events"))
    }
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
    val deploymentId = change.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).getOrElse(throw new IllegalArgumentException("No deployment id found in Http Header"))
    waitForDeploymentId(deploymentId.value, maxWait)
  }

  def waitForPod(podId: PathId): PodStatus = {
    eventually {
      Try(marathon.status(podId)).map(_.value).toOption.filter(_.status == PodState.Stable).get
    }
  }
}

/**
  * Base trait that starts a local marathon but doesn't have mesos/zookeeper yet
  */
trait LocalMarathonTest
    extends ExitDisabledTest
    with MarathonTest
    with ScalaFutures {

  this: MesosTest with ZookeeperServerTest =>

  val marathonArgs = Map.empty[String, String]

  lazy val marathonServer = LocalMarathon(autoStart = false, suiteName = suiteName, masterUrl = mesosMasterUrl,
    zkUrl = s"zk://${zkServer.connectUri}/marathon",
    conf = marathonArgs)
  lazy val marathonUrl = s"http://localhost:${marathonServer.httpPort}"

  val testBasePath: PathId = PathId("/")
  lazy val marathon = marathonServer.client
  lazy val appMock: AppMockFacade = new AppMockFacade()

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    marathonServer.start().futureValue(Timeout(90.seconds))
    callbackEndpoint
  }

  abstract override def afterAll(): Unit = {
    Try(marathonServer.close())
    super.afterAll()
  }
}

/**
  * Trait that has one Marathon instance, zk, and a Mesos via mesos-local ready to go.
  *
  * This should be used for simple tests that do not require multiple masters.
  */
trait EmbeddedMarathonTest extends Suite with StrictLogging with ZookeeperServerTest with MesosLocalTest with LocalMarathonTest

/**
  * Trait that has one Marathon instance, zk, and a Mesos cluster ready to go.
  *
  * It allows to stop and start Mesos masters and agents. See [[mesosphere.marathon.integration.TaskUnreachableIntegrationTest]]
  * for an example.
  */
trait EmbeddedMarathonMesosClusterTest extends Suite with StrictLogging with ZookeeperServerTest with MesosClusterTest with LocalMarathonTest

/**
  * Trait that has a Marathon cluster, zk, and Mesos via mesos-local ready to go.
  *
  * It provides multiple Marathon instances. This allows e.g. leadership rotation.
  */
trait MarathonClusterTest extends Suite with StrictLogging with ZookeeperServerTest with MesosLocalTest with LocalMarathonTest {
  val numAdditionalMarathons = 2
  lazy val additionalMarathons = 0.until(numAdditionalMarathons).map { _ =>
    LocalMarathon(autoStart = false, suiteName = suiteName, masterUrl = mesosMasterUrl,
      zkUrl = s"zk://${zkServer.connectUri}/marathon",
      conf = marathonArgs)
  }
  lazy val marathonFacades = marathon +: additionalMarathons.map(_.client)

  override def beforeAll(): Unit = {
    super.beforeAll()
    Future.sequence(additionalMarathons.map(_.start())).futureValue(Timeout(60.seconds))
  }

  override def afterAll(): Unit = {
    Try(additionalMarathons.foreach(_.close()))
    super.afterAll()
  }

  def nonLeader(leader: ITLeaderResult): MarathonFacade = {
    marathonFacades.find(!_.url.contains(leader.port)).get
  }
}
