package mesosphere.marathon.integration.setup

import java.io.File
import org.scalatest._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try
import java.util.UUID
import mesosphere.marathon.health.HealthCheck

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
class IntegrationHealthCheck(portIndex:Int, private var state: Boolean) {

  case class HealthStatusChange(deadLine:Deadline, state:Boolean)
  private[this] var changes = List.empty[HealthStatusChange]

  lazy val id:String = UUID.randomUUID().toString

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
    state
  }

  def toHealthCheck: HealthCheck = HealthCheck(path=Some(s"/health/$id"), initialDelay=1.second, interval=1.second, portIndex=portIndex )
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
    ProcessKeeper.stopAllProcesses()
    ProcessKeeper.stopAllServices()
  }

  override def handleEvent(event: CallbackEvent): Unit = events.enqueue(event)

  def waitForEvent(kind:String, maxWait:FiniteDuration=30.seconds): CallbackEvent = {
    def nextEvent : Option[CallbackEvent] = if (events.isEmpty) None else {
      val event = events.dequeue()
      if (event.eventType==kind) Some(event) else None
    }
    waitFor(s"event $kind to arrive", nextEvent, maxWait)
  }

  def waitForTasks(appId:String, num:Int, maxWait:FiniteDuration=30.seconds): List[ITEnrichedTask] = {
    def checkTasks : Option[List[ITEnrichedTask]] = {
      val tasks = marathon.tasks(appId).value
      if (tasks.size==num) Some(tasks) else None
    }
    waitFor(s"$num tasks to launch", checkTasks, maxWait)
  }

  def waitFor[T](description:String, fn: => Option[T],  maxWait:FiniteDuration): T = {
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

  def healthCheck(portIndex:Int, state:Boolean) : IntegrationHealthCheck = {
    val check = new IntegrationHealthCheck(portIndex, state)
    ExternalMarathonIntegrationTest.healthChecks += check
    check
  }
}


