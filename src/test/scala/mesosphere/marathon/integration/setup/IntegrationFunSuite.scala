package mesosphere.marathon.integration.setup

import java.io.File
import org.scalatest._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.api.v1.AppDefinition
import org.joda.time.DateTime

/**
 * All integration tests should mix in this trait.
 * Integration tests need a special set up and can take a long time.
 * So it is not desirable, that these kind of tests run every time all the unit tests run.
 *
 * All derived test suites will only run, if the system property integration is set.
 * You can set it with
 * maven: mvn -Dintegration=true test
 */
trait IntegrationFunSuite extends FunSuite {
  private lazy val isIntegration = {
    val prop = sys.props.get("integration").flatMap(p=> Try(p.toBoolean).toOption).getOrElse(false)
    val idea = sys.env.values.exists(_=="org.jetbrains.plugins.scala.testingSupport.scalaTest.ScalaTestRunner")
    prop || idea
  }
  override protected def test(testName: String, testTags: Tag*)(testFun: => Unit): Unit = {
    if (isIntegration) super.test(testName, testTags:_*)(testFun)
  }
}

/**
 * Trait for running external marathon instances.
 */
trait ExternalMarathonIntegrationTest {

  def config:IntegrationTestConfig

  def env = {
    val envName = "MESOS_NATIVE_LIBRARY"
    if (sys.env.contains(envName)) sys.env else sys.env + (envName->config.mesosLib)
  }

  def startMarathon(port:Int, args:String*): MarathonFacade = {
    val cwd = new File(".")
    ProcessKeeper.startMarathon(cwd, env, List("--http_port", port.toString, "--zk", config.zk) ++ args.toList)
    new MarathonFacade(s"http://localhost:$port")
  }

  def handleEvent(event:CallbackEvent) : Unit
}

object ExternalMarathonIntegrationTest {
  val listener = mutable.ListBuffer.empty[ExternalMarathonIntegrationTest]
  val healthChecks = mutable.ListBuffer.empty[IntegrationHealthCheck]
}


/**
 * Health check helper to define health behaviour of launched applications
 */
class IntegrationHealthCheck(val appId:String, val versionId:String, val port:Int, var state: Boolean, var lastUpdate:DateTime=new DateTime(0)) {

  case class HealthStatusChange(deadLine:Deadline, state:Boolean)
  private[this] var changes = List.empty[HealthStatusChange]

  def afterDelay(delay:FiniteDuration, state: Boolean) {
    val item = HealthStatusChange(delay.fromNow, state)
    def insert(ag: List[HealthStatusChange]): List[HealthStatusChange] = {
      if (ag.isEmpty || item.deadLine < ag.head.deadLine) item :: ag
      else ag.head :: insert(ag.tail)
    }
    changes = insert(changes)
  }

  def healthy : Boolean = {
    val (past, future) = changes.partition(_.deadLine.isOverdue())
    state = past.reverse.headOption.fold(state)(_.state)
    changes = future
    lastUpdate = DateTime.now()
    state
  }

  def forVersion(versionId:String, state:Boolean) = {
    val result = new IntegrationHealthCheck(appId, versionId, port, state)
    ExternalMarathonIntegrationTest.healthChecks += result
    result
  }

  def pingSince(duration:Duration) : Boolean = DateTime.now.minusMillis(duration.toMillis.toInt).isAfter(lastUpdate)
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
trait SingleMarathonIntegrationTest extends ExternalMarathonIntegrationTest with BeforeAndAfterAllConfigMap { self:Suite =>

  var config = IntegrationTestConfig(ConfigMap.empty)
  lazy val marathon:MarathonFacade = new MarathonFacade(s"http://localhost:${config.singleMarathonPort}")
  val events = new mutable.SynchronizedQueue[CallbackEvent]()

  override protected def beforeAll(configMap:ConfigMap): Unit = {
    config = IntegrationTestConfig(configMap)
    super.beforeAll(configMap)
    ProcessKeeper.startMesosLocal()
    startMarathon(config.singleMarathonPort, "--master", config.mesos, "--event_subscriber", "http_callback")
    ProcessKeeper.startHttpService(config.httpPort, config.cwd)
    ExternalMarathonIntegrationTest.listener += this
    marathon.cleanUp(withSubscribers = true)
    marathon.subscribe(s"http://localhost:${config.httpPort}/callback")
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    marathon.cleanUp(withSubscribers = true)
    ExternalMarathonIntegrationTest.listener -= this
    ExternalMarathonIntegrationTest.healthChecks.clear()
    ProcessKeeper.stopAllServices()
    ProcessKeeper.stopAllProcesses()
    ProcessKeeper.stopOSProcesses("mesosphere.marathon.integration.setup.AppMock")
  }

  override def handleEvent(event: CallbackEvent): Unit = events.enqueue(event)

  def waitForEvent(kind:String, maxWait:FiniteDuration=30.seconds): CallbackEvent = {
    def nextEvent : Option[CallbackEvent] = if (events.isEmpty) None else {
      val event = events.dequeue()
      if (event.eventType==kind) Some(event) else None
    }
    waitFor(s"event $kind to arrive", maxWait)(nextEvent)
  }

  def waitForTasks(appId:String, num:Int, maxWait:FiniteDuration=30.seconds): List[ITEnrichedTask] = {
    def checkTasks : Option[List[ITEnrichedTask]] = {
      val tasks = Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil)
      if (tasks.size==num) Some(tasks) else None
    }
    waitFor(s"$num tasks to launch", maxWait)(checkTasks )
  }

  def validFor(description:String, until:FiniteDuration)(valid: => Boolean): Boolean = {
    val deadLine = until.fromNow
    def checkValid() : Boolean = {
      if (!valid) throw new IllegalStateException(s"$description not valid for $until. Give up.")
      if (deadLine.isOverdue()) true else {
        Thread.sleep(100)
        checkValid()
      }
    }
    checkValid()
  }

  def waitUntil(description:String, maxWait:FiniteDuration)(fn: => Boolean) = {
    waitFor(description, maxWait) {
      if (fn) Some(true) else None
    }
  }

  def waitFor[T](description:String,  maxWait:FiniteDuration)(fn: => Option[T]): T = {
    val deadLine = maxWait.fromNow
    def next() : T = {
      if (deadLine.isOverdue()) throw new AssertionError(s"Waiting for $description took longer than $maxWait. Give up.")
      fn match {
        case Some(t) => t
        case None => Thread.sleep(100); next()
      }
    }
    next()
  }

  def appProxy(appId:String, versionId:String, instances:Int) : AppDefinition = {
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[AppMock].getName
    val exec = s"""$javaExecutable -classpath $classPath $main http://localhost:${config.httpPort}/health/$appId/$versionId"""
    val health = HealthCheck(gracePeriod=10.second, interval=1.second, maxConsecutiveFailures=5)
    AppDefinition(appId, exec, executor="//cmd", instances=instances, cpus=0.5, mem=128, healthChecks=Set(health))

  }

  def appProxyChecks(appId:String, versionId:String, state:Boolean) : Seq[IntegrationHealthCheck] = {
    marathon.app(appId).value.ports.map { port =>
      val check = new IntegrationHealthCheck(appId, versionId, port, state)
      ExternalMarathonIntegrationTest.healthChecks += check
      check
    }
  }
}


