package mesosphere.marathon
package integration

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.{ MarathonFacade, MesosFacade }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId

import scala.concurrent.{ ExecutionContext, Future }
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build.
  */
@IntegrationTest
class UpgradeIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest {

  import PathId._

  val zkURL = s"zk://${zkServer.connectUri}/marathon-$suiteName"

  case class Marathon149(
      suiteName: String,
      masterUrl: String,
      zkUrl: String,
      override val conf: Map[String, String] = Map.empty)(implicit
      val system: ActorSystem,
      val mat: Materializer,
      val ctx: ExecutionContext,
      val scheduler: Scheduler) extends BaseMarathon {

    // it'd be great to be able to execute in memory, but we can't due to GuiceFilter using a static :(
    override val processBuilder = {
      val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
      val jar = "/Users/kjeschkies/Projects/marathon/marathon-1.4.9/target/scala-2.11/marathon-assembly-1.4.9.jar"
      val cmd = Seq(java, "-Xmx1024m", "-Xms256m", "-XX:+UseConcMarkSweepGC", "-XX:ConcGCThreads=2",
        // lower the memory pressure by limiting threads.
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
        "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
        "-Dscala.concurrent.context.minThreads=2",
        "-Dscala.concurrent.context.maxThreads=32",
        s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-client",
        "-jar", jar
      ) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }

    override def activePids: Seq[String] = {
      val PIDRE = """^\s*(\d+)\s+\s*(.*)$""".r
      Process("jps -lv").!!.split("\n").collect {
        case PIDRE(pid, jvmArgs) if jvmArgs.contains(uuid) => pid
      }(collection.breakOut)
    }
  }

  case class Fixture(val marathonServer: BaseMarathon)(
      implicit
      val system: ActorSystem,
      val mat: Materializer,
      val ctx: ExecutionContext,
      val scheduler: Scheduler) extends MarathonTest {

    override protected val logger = UpgradeIntegrationTest.this.logger
    override def marathonUrl: String = s"http://localhost:${marathonServer.httpPort}"
    override def marathon: MarathonFacade = marathonServer.client
    override def mesos: MesosFacade = UpgradeIntegrationTest.this.mesos
    override val testBasePath: PathId = PathId("/")
    override val suiteName: String = UpgradeIntegrationTest.this.suiteName
    override implicit def patienceConfig: PatienceConfig = PatienceConfig(UpgradeIntegrationTest.this.patienceConfig.timeout, UpgradeIntegrationTest.this.patienceConfig.interval)
    override def leadingMarathon = Future.successful(marathonServer)
  }

  "A simple app" should {
    "survive an upgrade cycle" in {

      val marathon149 = Marathon149(
        suiteName = s"$suiteName-149",
        masterUrl = mesosMasterUrl,
        zkUrl = zkURL
      )
      val marathon149F = Fixture(marathon149)
      marathon149.start().futureValue

      (marathon149F.marathon.info.entityJson \ "version").as[String] should be("1.4.9")

      Given("a new app")
      val app = marathon149F.appProxy(marathon149F.testBasePath / "app-149", "v1", instances = 1, healthCheck = None)

      When("The app is deployed")
      val result = marathon149F.marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      //      marathon149F.waitForDeployment(result)
      val tasksBeforeUpgrade = marathon149F.waitForTasks(app.id.toPath, 1)

      marathon149.stop().futureValue

      val marathonCurrent = LocalMarathon(
        suiteName = s"$suiteName-current",
        masterUrl = mesosMasterUrl,
        zkUrl = zkURL
      )
      val marathonCurrentF = Fixture(marathonCurrent)

      marathonCurrent.start().futureValue

      val info = marathonCurrentF.marathon.info
      (info.entityJson \ "version").as[String] should be("1.6.0-SNAPSHOT")

      val tasksAfterUpgrade = marathonCurrentF.marathon.tasks(app.id.toPath).value
      tasksAfterUpgrade should be(tasksBeforeUpgrade)
      //      val tasksAfterUpgrade = marathonCurrentF.waitForTasks(app.id.toPath, 1)

      marathonCurrent.stop().futureValue

      marathonCurrentF.teardown()
    }
  }
}
