package mesosphere.marathon
package integration

import java.io.File
import java.net.URL
import java.nio.file.Files

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.{ MarathonFacade, MesosFacade }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.marathon.state.PathId
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ ExecutionContext, Future }
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build.
  */
@IntegrationTest
class UpgradeIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with Eventually {

  import PathId._

  val zkURL = s"zk://${zkServer.connectUri}/marathon-$suiteName"

  val marathon149 = Marathon149(suiteName = s"$suiteName-149", masterUrl = mesosMasterUrl, zkUrl = zkURL)

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Download Marathon releases
    marathon149.downloadAndExtract()
  }

  case class Marathon149(
      suiteName: String,
      masterUrl: String,
      zkUrl: String,
      override val conf: Map[String, String] = Map.empty)(implicit
      val system: ActorSystem,
      val mat: Materializer,
      val ctx: ExecutionContext,
      val scheduler: Scheduler) extends BaseMarathon {

    val marathon149Package = Files.createTempDirectory(s"marathon-1.4.9").toFile
    marathon149Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon149Package, "marathon-1.4.9.tgz")
      logger.info(s"Downloading Marathon 1.4.9 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.4.9/marathon-1.4.9.tgz"), tarball)
      IO.extractTGZip(tarball, marathon149Package)
    }

    // it'd be great to be able to execute in memory, but we can't due to GuiceFilter using a static :(
    override val processBuilder = {
      val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
      val jar = new File(marathon149Package, "marathon-1.4.9/target/scala-2.11/marathon-assembly-1.4.9.jar").getCanonicalPath
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

      //      marathonCurrentF.waitForDeployment(eventually(marathonCurrentF.marathon.deleteApp(app.id.toPath, force = true)))
      marathonCurrent.close()

      //      marathonCurrentF.teardown()
    }
  }
}
