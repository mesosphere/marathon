package mesosphere.marathon.integration

import java.io.File
import org.scalatest._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Try

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
  private lazy val isIntegration = sys.props.get("integration").flatMap(p=> Try(p.toBoolean).toOption).getOrElse(false)
  override protected def test(testName: String, testTags: Tag*)(testFun: => Unit): Unit = {
    if (isIntegration) super.test(testName, testTags:_*)(testFun)
  }
}

/**
 * Trait for running external marathon instances.
 */
trait ExternalMarathonIntegrationTest {

  def cwd = new File(".")
  def config:ConfigMap
  def env = {
    if (sys.env.contains("MESOS_NATIVE_LIBRARY")) sys.env
    else sys.env + ("MESOS_NATIVE_LIBRARY"->config.getOptional("mosos_lib").getOrElse("/usr/local/lib/libmesos.dylib"))
  }

  def localPort = config.getOptional[Int]("port").getOrElse(11211)

  def startMarathon(port:Int, args:String*): MarathonFacade = {
    val cwd = new File(".")
    val zk = config.getOptional[String]("zk").getOrElse("zk://localhost:2181/test")
    ProcessKeeper.startMarathon(cwd, env, List("--http_port", port.toString, "--zk", zk)++args.toList)
    new MarathonFacade(s"http://localhost:$port")
  }

  def handleEvent(event:CallbackEvent) : Unit
}

object ExternalMarathonIntegrationTest {
  val listener = mutable.ListBuffer.empty[ExternalMarathonIntegrationTest]
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

  var config: ConfigMap = ConfigMap.empty
  lazy val marathonPort = config.getOptional[Int]("marathonPort").getOrElse(8080 + (math.random*100).toInt)
  lazy val marathon:MarathonFacade = new MarathonFacade(s"http://localhost:$marathonPort")
  val events = new mutable.SynchronizedQueue[CallbackEvent]()

  override protected def beforeAll(configMap:ConfigMap): Unit = {
    config = configMap
    super.beforeAll(configMap)
    startMarathon(marathonPort, "--master", "local", "--event_subscriber", "http_callback")
    ProcessKeeper.startHttpService(localPort, cwd.getAbsolutePath)
    ExternalMarathonIntegrationTest.listener += this
    marathon.cleanUp(withSubscribers = true)
    marathon.subscribe(s"http://localhost:$localPort/callback")
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    marathon.cleanUp(withSubscribers = true)
    ExternalMarathonIntegrationTest.listener -= this
    ProcessKeeper.stopAllProcesses()
  }

  override def handleEvent(event: CallbackEvent): Unit = events.enqueue(event)

  def waitForEvent(kind:String, maxWait:FiniteDuration=15.seconds): CallbackEvent = {
    val deadLine = maxWait.fromNow
    def next() : CallbackEvent = {
      if (deadLine.isOverdue()) {
        throw new AssertionError(s"Waiting for event $kind longer than $maxWait. Give up.")
      } else if (events.isEmpty) {
        Thread.sleep(100)
        next()
      } else {
        val head = events.dequeue()
        if (head.eventType==kind) head else next()
      }
    }
    next()
  }
}


