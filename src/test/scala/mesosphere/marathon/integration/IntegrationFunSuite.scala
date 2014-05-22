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
 * Configuration used in integration test.
 * Pass parameter from scala test by command line and create via ConfigMap.
 * mvn: pass this parameter via command line -DtestConfig="cwd=/tmp,zk=zk://somehost"
 */
case class IntegrationTestConfig(

  //current working directory for all processes to span: defaults to .
  cwd:String,

  //zookeeper url. defaults to zk://localhost:2181/test
  zk:String,

  //url to mesos master. defaults to local
  mesos:String,

  //mesosLib: path to the native mesos lib. Defaults to /usr/local/lib/libmesos.dylib
  mesosLib:String,

  //for single marathon tests, the marathon port to use.
  singleMarathonPort:Int,

  //the port for the local http interface. Defaults dynamically to a port [11211-11311]
  httpPort:Int
)

object IntegrationTestConfig {
  def apply(config:ConfigMap) : IntegrationTestConfig = {
    val cwd = config.getOptional[String]("cwd").getOrElse(".")
    val zk = config.getOptional[String]("zk").getOrElse("zk://localhost:2181/test")
    val mesos = config.getOptional[String]("mesos").getOrElse("local")
    val mesosLib = config.getOptional[String]("mesosLib").getOrElse("/usr/local/lib/libmesos.dylib")
    val httpPort = config.getOptional[Int]("httpPort").getOrElse(11211 + (math.random*100).toInt)
    val singleMarathonPort = config.getOptional[Int]("httpPort").getOrElse(8080 + (math.random*100).toInt)
    IntegrationTestConfig(cwd, zk, mesos, mesosLib, singleMarathonPort, httpPort)
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


