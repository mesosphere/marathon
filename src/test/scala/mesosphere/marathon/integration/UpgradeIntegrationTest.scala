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

  val marathon149 = Marathon149(suiteName = s"$suiteName-1-4-9", mesosMasterUrl, zkURL)
  val marathon156 = Marathon156(suiteName = s"$suiteName-1-5-6", mesosMasterUrl, zkURL)
  val marathon16322 = Marathon16322(suiteName = s"$suiteName-1-6-322", mesosMasterUrl, zkURL)
  val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterUrl, zkUrl = zkURL)

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Download Marathon releases
    marathon149.downloadAndExtract()
    marathon156.downloadAndExtract()
    marathon16322.downloadAndExtract()
  }

  case class Marathon149(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

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
  }

  case class Marathon156(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    val marathon156Package = Files.createTempDirectory(s"marathon-1.5.6").toFile
    marathon156Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon156Package, "marathon-1.5.6.tgz")
      logger.info(s"Downloading Marathon 1.5.6 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.5.6/marathon-1.5.6.tgz"), tarball)
      IO.extractTGZip(tarball, marathon156Package)
    }

    // it'd be great to be able to execute in memory, but we can't due to GuiceFilter using a static :(
    override val processBuilder = {
      val bin = new File(marathon156Package, "marathon-1.5.6/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2",
        // lower the memory pressure by limiting threads.
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
        "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
        "-Dscala.concurrent.context.minThreads=2",
        "-Dscala.concurrent.context.maxThreads=32",
        s"-DmarathonUUID=$uuid -DtestSuite=$suiteName"
      ) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon16322(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    val marathon16322Package = Files.createTempDirectory(s"marathon-1.6.322").toFile
    marathon16322Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon16322Package, "marathon-1.6.322-2bf46b341.tgz")
      logger.info(s"Downloading Marathon 1.6.322 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.6.322/marathon-1.6.322-2bf46b341.tgz"), tarball)
      IO.extractTGZip(tarball, marathon16322Package)
    }

    // it'd be great to be able to execute in memory, but we can't due to GuiceFilter using a static :(
    override val processBuilder = {
      val bin = new File(marathon16322Package, "marathon-1.6.322-2bf46b341/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2",
        // lower the memory pressure by limiting threads.
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
        "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
        "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
        "-Dscala.concurrent.context.minThreads=2",
        "-Dscala.concurrent.context.maxThreads=32",
        s"-DmarathonUUID=$uuid -DtestSuite=$suiteName"
      ) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
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

      Given("a new app in Marathon 1.4.6")
      val app_146 = marathon149F.appProxy(marathon149F.testBasePath / "app-149", "v1", instances = 1, healthCheck = None)

      When("The app is deployed")
      val result = marathon149F.marathon.createAppV2(app_146)

      Then("The app is created")
      result should be(Created)
      //      marathon149F.waitForDeployment(result)
      val tasksBeforeUpgrade = marathon149F.waitForTasks(app_146.id.toPath, 1)

      marathon149.stop().futureValue

      // Pass upgrade to 1.5.6
      When("Marathon is upgraded to 1.5.6")
      marathon156.start().futureValue
      (marathon156.client.info.entityJson \ "version").as[String] should be("1.5.6")

      Then("All apps from 1.4.9 are still running")
      val tasksAfterUpgradeTo156 = marathon156.client.tasks(app_146.id.toPath).value
      tasksAfterUpgradeTo156 should be(tasksBeforeUpgrade)

      marathon156.stop().futureValue

      // Pass upgrade to 1.6.322
      When("Marathon is upgraded to 1.6.322")
      marathon16322.start().futureValue
      (marathon16322.client.info.entityJson \ "version").as[String] should be("1.6.322")

      Then("All apps from 1.4.9 are still running")
      val tasksAfterUpgradeTo16322 = marathon16322.client.tasks(app_146.id.toPath).value
      tasksAfterUpgradeTo16322 should be(tasksBeforeUpgrade)

      marathon16322.stop().futureValue

      // Pass upgrade to current
      When("Marathon is upgraded to the current version")
      marathonCurrent.start().futureValue
      (marathonCurrent.client.info.entityJson \ "version").as[String] should be("1.6.0-SNAPSHOT")

      Then("All apps from 1.4.9 are still running")
      val tasksAfterUpgradeToCurrent = marathonCurrent.client.tasks(app_146.id.toPath).value
      tasksAfterUpgradeToCurrent should be(tasksBeforeUpgrade)

      marathonCurrent.close()
      //      marathonCurrentF.teardown()
    }
  }
}
