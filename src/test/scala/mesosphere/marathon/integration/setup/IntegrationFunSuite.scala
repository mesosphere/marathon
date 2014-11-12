package mesosphere.marathon.integration.setup

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import mesosphere.marathon.state.PathId
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooKeeper }
import org.scalatest._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition
import org.joda.time.DateTime
import scala.collection.JavaConverters._

/**
  * All integration tests should be marked with this tag.
  * Integration tests need a special set up and can take a long time.
  * So it is not desirable, that these kind of tests run every time all the unit tests run.
  */
object IntegrationTag extends Tag("integration")

/**
  * Convenience trait, which will mark all test cases as integration tests.
  */
trait IntegrationFunSuite extends FunSuite {
  override protected def test(testName: String, testTags: Tag*)(testFun: => Unit): Unit = {
    super.test(testName, IntegrationTag +: testTags: _*)(testFun)
  }
}

/**
  * Trait for running external marathon instances.
  */
trait ExternalMarathonIntegrationTest {

  def config: IntegrationTestConfig

  def env = {
    val envName = "MESOS_NATIVE_LIBRARY"
    if (sys.env.contains(envName)) sys.env else sys.env + (envName -> config.mesosLib)
  }

  def startMarathon(port: Int, args: String*): MarathonFacade = {
    val cwd = new File(".")
    ProcessKeeper.startMarathon(cwd, env, List("--http_port", port.toString, "--zk", config.zk) ++ args.toList)
    new MarathonFacade(s"http://localhost:$port")
  }

  def handleEvent(event: CallbackEvent): Unit
}

object ExternalMarathonIntegrationTest {
  val listener = mutable.ListBuffer.empty[ExternalMarathonIntegrationTest]
  val healthChecks = mutable.ListBuffer.empty[IntegrationHealthCheck]
}

/**
  * Health check helper to define health behaviour of launched applications
  */
class IntegrationHealthCheck(val appId: PathId, val versionId: String, val port: Int, var state: Boolean, var lastUpdate: DateTime = DateTime.now) {

  case class HealthStatusChange(deadLine: Deadline, state: Boolean)
  private[this] var changes = List.empty[HealthStatusChange]
  private[this] var healthAction = (check: IntegrationHealthCheck) => {}
  var pinged = false

  def afterDelay(delay: FiniteDuration, state: Boolean) {
    val item = HealthStatusChange(delay.fromNow, state)
    def insert(ag: List[HealthStatusChange]): List[HealthStatusChange] = {
      if (ag.isEmpty || item.deadLine < ag.head.deadLine) item :: ag
      else ag.head :: insert(ag.tail)
    }
    changes = insert(changes)
  }

  def withHealthAction(fn: (IntegrationHealthCheck) => Unit): this.type = {
    healthAction = fn
    this
  }

  def healthy: Boolean = {
    healthAction(this)
    pinged = true
    val (past, future) = changes.partition(_.deadLine.isOverdue())
    state = past.reverse.headOption.fold(state)(_.state)
    changes = future
    lastUpdate = DateTime.now()
    println(s"Get health state from: $appId $versionId $port -> $state")
    state
  }

  def forVersion(versionId: String, state: Boolean) = {
    val result = new IntegrationHealthCheck(appId, versionId, port, state)
    ExternalMarathonIntegrationTest.healthChecks += result
    result
  }

  def pingSince(duration: Duration): Boolean = DateTime.now.minusMillis(duration.toMillis.toInt).isBefore(lastUpdate)
}

/**
  * Convenient trait to test against one marathon instance.
  * Following things are managed at start:
  * (-) a marathon instance is launched.
  * (-) all existing groups, apps and event listeners are removed.
  * (-) a local http server is launched.
  * (-) a callback event handler is registered.
  * (-) this test suite is registered as callback event listener. every event is stored in a queue.
  * (-) a marathonFacade is provided
  *
  * After the test is finished, everything will be clean up.
  */
trait SingleMarathonIntegrationTest extends ExternalMarathonIntegrationTest with BeforeAndAfterAllConfigMap { self: Suite =>

  var config = IntegrationTestConfig(ConfigMap.empty)
  lazy val marathon: MarathonFacade = new MarathonFacade(s"http://localhost:${config.singleMarathonPort}")
  val events = new ConcurrentLinkedQueue[CallbackEvent]()

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    config = IntegrationTestConfig(configMap)
    super.beforeAll(configMap)
    ProcessKeeper.startZooKeeper(config.zkPort, "/tmp/foo")
    ProcessKeeper.startMesosLocal()
    cleanMarathonState()
    startMarathon(config.singleMarathonPort, "--master", config.master, "--event_subscriber", "http_callback")
    ProcessKeeper.startHttpService(config.httpPort, config.cwd)
    ExternalMarathonIntegrationTest.listener += this
    marathon.subscribe(s"http://localhost:${config.httpPort}/callback")
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    cleanUp(withSubscribers = true)
    ExternalMarathonIntegrationTest.listener -= this
    ExternalMarathonIntegrationTest.healthChecks.clear()
    ProcessKeeper.stopAllServices()
    ProcessKeeper.stopAllProcesses()
    ProcessKeeper.stopOSProcesses("mesosphere.marathon.integration.setup.AppMock")
  }

  def cleanMarathonState() {
    val watcher = new Watcher { override def process(event: WatchedEvent): Unit = println(event) }
    val zooKeeper = new ZooKeeper(config.zkHostAndPort, 30 * 1000, watcher)
    def deletePath(path: String) {
      if (zooKeeper.exists(path, false) != null) {
        val children = zooKeeper.getChildren(path, false)
        children.asScala.foreach(sub => deletePath(s"$path/$sub"))
        zooKeeper.delete(path, -1)
      }
    }
    deletePath(config.zkPath)
    zooKeeper.close()
  }

  override def handleEvent(event: CallbackEvent): Unit = events.add(event)

  def waitForEvent(kind: String, maxWait: FiniteDuration = 30.seconds): CallbackEvent = waitForEventWith(kind, _ => true, maxWait)
  def waitForChange(change: RestResult[ITDeploymentResult], maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForEventWith("deployment_success", _.info.getOrElse("id", "") == change.value.deploymentId, maxWait)
  }

  def waitForEventWith(kind: String, fn: CallbackEvent => Boolean, maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    def nextEvent: Option[CallbackEvent] = if (events.isEmpty) None else {
      val event = events.poll()
      if (event.eventType == kind) Some(event) else None
    }
    waitFor(s"event $kind to arrive", maxWait)(nextEvent)
  }

  def waitForTasks(appId: PathId, num: Int, maxWait: FiniteDuration = 30.seconds): List[ITEnrichedTask] = {
    def checkTasks: Option[List[ITEnrichedTask]] = {
      val tasks = Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil)
      if (tasks.size == num) Some(tasks) else None
    }
    waitFor(s"$num tasks to launch", maxWait)(checkTasks)
  }

  def waitForHealthCheck(check: IntegrationHealthCheck, maxWait: FiniteDuration = 30.seconds) = {
    waitUntil("Health check to get queried", maxWait) { check.pinged }
  }

  def validFor(description: String, until: FiniteDuration)(valid: => Boolean): Boolean = {
    val deadLine = until.fromNow
    def checkValid(): Boolean = {
      if (!valid) throw new IllegalStateException(s"$description not valid for $until. Give up.")
      if (deadLine.isOverdue()) true else {
        Thread.sleep(100)
        checkValid()
      }
    }
    checkValid()
  }

  def waitUntil(description: String, maxWait: FiniteDuration)(fn: => Boolean) = {
    waitFor(description, maxWait) {
      if (fn) Some(true) else None
    }
  }

  def waitFor[T](description: String, maxWait: FiniteDuration)(fn: => Option[T]): T = {
    val deadLine = maxWait.fromNow
    def next(): T = {
      if (deadLine.isOverdue()) throw new AssertionError(s"Waiting for $description took longer than $maxWait. Give up.")
      fn match {
        case Some(t) => t
        case None    => Thread.sleep(100); next()
      }
    }
    next()
  }

  def appProxy(appId: PathId, versionId: String, instances: Int, withHealth: Boolean = true, dependencies: Set[PathId] = Set.empty): AppDefinition = {
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[AppMock].getName
    val exec = Some(s"""$javaExecutable -classpath $classPath $main $appId $versionId http://localhost:${config.httpPort}/health$appId/$versionId""")
    val health = if (withHealth) Set(HealthCheck(gracePeriod = 20.second, interval = 1.second, maxConsecutiveFailures = 10)) else Set.empty[HealthCheck]
    AppDefinition(appId, exec, executor = "//cmd", instances = instances, cpus = 0.5, mem = 128.0, healthChecks = health, dependencies = dependencies)
  }

  def appProxyCheck(appId: PathId, versionId: String, state: Boolean): IntegrationHealthCheck = {
    //this is used for all instances, as long as there is no specific instance check
    //the specific instance check has also a specific port, which is assigned by mesos
    val check = new IntegrationHealthCheck(appId, versionId, 0, state)
    ExternalMarathonIntegrationTest.healthChecks
      .filter(c => c.appId == appId && c.versionId == versionId)
      .foreach(ExternalMarathonIntegrationTest.healthChecks -= _)
    ExternalMarathonIntegrationTest.healthChecks += check
    check
  }

  def taskProxyChecks(appId: PathId, versionId: String, state: Boolean): Seq[IntegrationHealthCheck] = {
    marathon.tasks(appId).value.flatMap(_.ports).map { port =>
      val check = new IntegrationHealthCheck(appId, versionId, port, state)
      ExternalMarathonIntegrationTest.healthChecks
        .filter(c => c.appId == appId && c.versionId == versionId)
        .foreach(ExternalMarathonIntegrationTest.healthChecks -= _)
      ExternalMarathonIntegrationTest.healthChecks += check
      check
    }
  }

  def cleanUp(withSubscribers: Boolean = false, maxWait: FiniteDuration = 30.seconds) {
    events.clear()
    ExternalMarathonIntegrationTest.healthChecks.clear()
    waitForChange(marathon.deleteRoot(force = true))
    waitUntil("cleanUp", maxWait) { marathon.listApps.value.isEmpty && marathon.listGroups.value.isEmpty }
    if (withSubscribers) marathon.listSubscribers.value.urls.foreach(marathon.unsubscribe)
    events.clear()
  }
}

