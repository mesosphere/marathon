package mesosphere.marathon.integration.setup

import java.io.File

import akka.actor.ActorSystem
import mesosphere.marathon.state.PathId
import org.joda.time.DateTime
import org.scalatest._

import scala.collection.mutable
import scala.concurrent.duration._

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

  implicit lazy val system = ActorSystem()

  def config: IntegrationTestConfig

  def env = {
    val envName = "MESOS_NATIVE_JAVA_LIBRARY"
    if (sys.env.contains(envName)) sys.env else sys.env + (envName -> config.mesosLib)
  }

  def startMarathon(port: Int, args: String*): Unit = {
    val cwd = new File(".")
    ProcessKeeper.startMarathon(
      cwd, env, List("--http_port", port.toString, "--zk", config.zk) ++ args.toList,
      processName = s"marathon_$port"
    )
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

