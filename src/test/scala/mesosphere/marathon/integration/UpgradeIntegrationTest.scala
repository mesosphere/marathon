package mesosphere.marathon
package integration

import java.io.File
import java.net.URL
import java.nio.file.Files

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.marathon.state.PathId
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually

import scala.concurrent.ExecutionContext
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build. In each step we verfiy that all apps are still up and running.
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

    val marathon149Package = Files.createTempDirectory("marathon-1.4.9").toFile
    marathon149Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon149Package, "marathon-1.4.9.tgz")
      logger.info(s"Downloading Marathon 1.4.9 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.4.9/marathon-1.4.9.tgz"), tarball)
      IO.extractTGZip(tarball, marathon149Package)
    }

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

    val marathon156Package = Files.createTempDirectory("marathon-1.5.6").toFile
    marathon156Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon156Package, "marathon-1.5.6.tgz")
      logger.info(s"Downloading Marathon 1.5.6 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.5.6/marathon-1.5.6.tgz"), tarball)
      IO.extractTGZip(tarball, marathon156Package)
    }

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

    val marathon16322Package = Files.createTempDirectory("marathon-1.6.322").toFile
    marathon16322Package.deleteOnExit()

    def downloadAndExtract() = {
      val tarball = new File(marathon16322Package, "marathon-1.6.322-2bf46b341.tgz")
      logger.info(s"Downloading Marathon 1.6.322 to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(new URL("https://downloads.mesosphere.com/marathon/releases/1.6.322/marathon-1.6.322-2bf46b341.tgz"), tarball)
      IO.extractTGZip(tarball, marathon16322Package)
    }

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

  "A simple app" should {
    "survive an upgrade cycle" in {

      // Start apps in 1.4.9
      Given("A Marathon 1.4.9 is running")
      val f = new Fixture()
      marathon149.start().futureValue
      (marathon149.client.info.entityJson \ "version").as[String] should be("1.4.9")

      And("a new app in Marathon 1.4.9")
      val app_149 = f.appProxy(f.testBasePath / "app-149", "v1", instances = 1, healthCheck = None)

      When("The app is deployed")
      marathon149.client.createAppV2(app_149) should be(Created)

      Then("The app is created")
      val tasksBeforeUpgrade = eventually {
        val tasks = marathon149.client.tasks(app_149.id.toPath).value
        tasks should have size (1)
        tasks.foreach { task =>
          task.state should be("TASK_RUNNING")
        }
        tasks
      }

      // Pass upgrade to 1.5.6
      When("Marathon is upgraded to 1.5.6")
      marathon149.stop().futureValue
      marathon156.start().futureValue
      (marathon156.client.info.entityJson \ "version").as[String] should be("1.5.6")

      And("a new app in Marathon 1.5.6 is added")
      val app_156 = f.appProxy(f.testBasePath / "app-156", "v1", instances = 1, healthCheck = None)
      marathon156.client.createAppV2(app_156) should be(Created)

      Then("All apps from 1.5.6 are running")
      val tasksBeforeUpgrade156 = eventually {
        val tasks = marathon156.client.tasks(app_156.id.toPath).value
        tasks should have size (1)
        tasks.foreach { task =>
          task.state should be("TASK_RUNNING")
        }
        tasks
      }

      And("All apps from 1.4.9 are still running")
      marathon156.client.tasks(app_149.id.toPath).value should be(tasksBeforeUpgrade)

      // Pass upgrade to 1.6.322
      When("Marathon is upgraded to 1.6.322")
      marathon156.stop().futureValue
      marathon16322.start().futureValue
      (marathon16322.client.info.entityJson \ "version").as[String] should be("1.6.322")

      Then("All apps from 1.4.9 are still running")
      marathon16322.client.tasks(app_149.id.toPath).value should be(tasksBeforeUpgrade)

      And("All apps from 1.5.6 are still running")
      marathon16322.client.tasks(app_156.id.toPath).value should be(tasksBeforeUpgrade156)

      // Pass upgrade to current
      When("Marathon is upgraded to the current version")
      marathon16322.stop().futureValue
      marathonCurrent.start().futureValue
      (marathonCurrent.client.info.entityJson \ "version").as[String] should be("1.6.0-SNAPSHOT")

      Then("All apps from 1.4.9 are still running")
      marathonCurrent.client.tasks(app_149.id.toPath).value should be(tasksBeforeUpgrade)

      And("All apps from 1.5.6 are still running")
      marathonCurrent.client.tasks(app_156.id.toPath).value should be(tasksBeforeUpgrade156)

      marathonCurrent.close()
      //TODO(karsten): We leak processes.
    }
  }

  // TODO(karsten): I'd love to have this test extend MarathonTest but I get NullPointerException for Akka.
  case class Fixture()(
      implicit
      val system: ActorSystem,
      val mat: Materializer,
      val ctx: ExecutionContext,
      val scheduler: Scheduler) extends MarathonTest {

    override protected val logger = UpgradeIntegrationTest.this.logger
    override def marathonUrl: String = ???
    override def marathon = ???
    override def mesos = UpgradeIntegrationTest.this.mesos
    override val testBasePath = PathId("/")
    override val suiteName: String = UpgradeIntegrationTest.this.suiteName
    override implicit def patienceConfig: PatienceConfig = PatienceConfig(UpgradeIntegrationTest.this.patienceConfig.timeout, UpgradeIntegrationTest.this.patienceConfig.interval)
    override def leadingMarathon = ???
  }
}
