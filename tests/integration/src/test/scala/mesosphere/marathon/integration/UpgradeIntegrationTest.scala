package mesosphere.marathon
package integration

import java.io.File
import java.net.URL
import java.nio.file.Files

import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.stream.Materializer
import mesosphere.marathon.core.pod.{HostNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.integration.facades.{AppMockFacade, ITEnrichedTask}
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.marathon.state.{PathId, PersistentVolume, PersistentVolumeInfo, VolumeMount}
import mesosphere.marathon.util.ZookeeperServerTest
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build. In each step we verfiy that all apps are still up and running.
  */
class UpgradeIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with MarathonAppFixtures with Eventually {

  import PathId._

  val zkURLBase = s"zk://${zkServer.connectUri}/marathon-$suiteName"

  val marathon149Artifact = MarathonArtifact("1.4.9", "marathon-1.4.9.tgz")
  val marathon1515Artifact = MarathonArtifact("1.5.15", "marathon-1.5.15.tgz")
  val marathon16549Artifact = MarathonArtifact("1.6.549", "marathon-1.6.549-aabf74302.tgz")

  // Configure Mesos to provide the Mesos containerizer with Docker image support.
  //  override lazy val mesosConfig = MesosConfig(
  //    launcher = "linux",
  //    isolation = Some("filesystem/linux,docker/runtime"),
  //    imageProviders = Some("docker"))

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Download Marathon releases
    marathon149Artifact.downloadAndExtract()
    marathon1515Artifact.downloadAndExtract()
    marathon16549Artifact.downloadAndExtract()

    marathon149Artifact.marathonPackage.deleteOnExit()
    marathon1515Artifact.marathonPackage.deleteOnExit()
    marathon16549Artifact.marathonPackage.deleteOnExit()
  }

  case class MarathonArtifact(marathonVersion: String, tarballName: String) {
    val marathonPackage: File = Files.createTempDirectory(s"marathon-$marathonVersion").toFile
    val downloadURL: URL = new URL(s"https://downloads.mesosphere.com/marathon/releases/$marathonVersion/$tarballName")

    def downloadAndExtract() = {
      val tarball = new File(marathonPackage, tarballName)
      logger.info(s"Downloading $tarballName to ${tarball.getCanonicalPath}")
      FileUtils.copyURLToFile(downloadURL, tarball)
      IO.extractTGZip(tarball, marathonPackage)
    }
  }

  case class Marathon149(marathonPackage: File, suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    override val processBuilder = {
      val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
      val jar = new File(marathonPackage, "marathon-1.4.9/target/scala-2.11/marathon-assembly-1.4.9.jar").getCanonicalPath
      val cmd = Seq(java, "-Xmx1024m", "-Xms256m", "-XX:+UseConcMarkSweepGC", "-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-client", "-jar", jar) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon1515(marathonPackage: File, suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    override val processBuilder = {
      val bin = new File(marathonPackage, "marathon-1.5.15/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName") ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon16549(marathonPackage: File, suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    override val processBuilder = {
      val bin = new File(marathonPackage, "marathon-1.6.549-aabf74302/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName") ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  "Ephemeral and persistent apps and pods" should {
    "survive an upgrade cycle" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {

      val zkUrl = s"$zkURLBase-upgrade-cycle"

      // Start apps in 1.4.9
      Given("A Marathon 1.4.9 is running")
      val marathon149 = Marathon149(marathon149Artifact.marathonPackage, suiteName = s"$suiteName-1-4-9", mesosMasterUrl, zkUrl)
      marathon149.start().futureValue
      (marathon149.client.info.entityJson \ "version").as[String] should be("1.4.9")

      And("new running apps in Marathon 1.4.9")
      val app_149_fail = appProxy(testBasePath / "app-149-fail", "v1", instances = 1, healthCheck = None)
      marathon149.client.createAppV2(app_149_fail) should be(Created)

      val app_149 = appProxy(testBasePath / "app-149", "v1", instances = 1, healthCheck = None)
      marathon149.client.createAppV2(app_149) should be(Created)

      patienceConfig
      eventually { marathon149 should have (runningTasksFor(app_149.id.toPath, 1)) }
      eventually { marathon149 should have (runningTasksFor(app_149_fail.id.toPath, 1)) }

      val originalApp149Tasks: List[ITEnrichedTask] = marathon149.client.tasks(app_149.id.toPath).value
      val originalApp149FailedTasks: List[ITEnrichedTask] = marathon149.client.tasks(app_149_fail.id.toPath).value

      When("Marathon 1.4.9 is shut down")
      marathon149.stop().futureValue

      And(s"App ${app_149_fail.id} fails")
      AppMockFacade.suicideAll(originalApp149FailedTasks)

      // Pass upgrade to 1.5.15
      And("Marathon is upgraded to 1.5.15")
      val marathon1515 = Marathon1515(marathon1515Artifact.marathonPackage, s"$suiteName-1-5-15", mesosMasterUrl, zkUrl)
      marathon1515.start().futureValue
      (marathon1515.client.info.entityJson \ "version").as[String] should be("1.5.15")

      And("new apps in Marathon 1.5.15 are added")
      val app_1515 = appProxy(testBasePath / "app-1515", "v1", instances = 1, healthCheck = None)
      marathon1515.client.createAppV2(app_1515) should be(Created)

      val app_1515_fail = appProxy(testBasePath / "app-1515-fail", "v1", instances = 1, healthCheck = None)
      marathon1515.client.createAppV2(app_1515_fail) should be(Created)

      Then("All apps from 1.5.15 are running")
      eventually { marathon1515 should have (runningTasksFor(app_1515.id.toPath, 1)) }
      eventually { marathon1515 should have (runningTasksFor(app_1515_fail.id.toPath, 1)) }

      val originalApp1515Tasks: List[ITEnrichedTask] = marathon1515.client.tasks(app_1515.id.toPath).value
      val originalApp1515FailedTasks: List[ITEnrichedTask] = marathon1515.client.tasks(app_1515_fail.id.toPath).value

      And("All apps from 1.4.9 are still running")
      marathon1515.client.tasks(app_149.id.toPath).value should contain theSameElementsAs (originalApp149Tasks)

      When("Marathon 1.5.15 is shut down")
      marathon1515.stop().futureValue

      And(s"App ${app_1515_fail.id} fails")
      AppMockFacade.suicideAll(originalApp1515FailedTasks)

      // Pass upgrade to 1.6.549
      And("Marathon is upgraded to 1.6.549")
      val marathon16549 = Marathon16549(marathon16549Artifact.marathonPackage, s"$suiteName-1-6-549", mesosMasterUrl, zkUrl)
      marathon16549.start().futureValue
      (marathon16549.client.info.entityJson \ "version").as[String] should be("1.6.549")

      And("new pods in Marathon 1.6.549 are added")
      val resident_pod_16549 = PodDefinition(
        id = testBasePath / "resident-pod-16549",
        role = "foo",
        containers = Seq(
          MesosContainer(
            name = "task1",
            exec = Some(raml.MesosExec(raml.ShellCommand("cd $MESOS_SANDBOX && echo 'start' >> pst1/foo && python -m SimpleHTTPServer $ENDPOINT_TASK1"))),
            resources = raml.Resources(cpus = 0.1, mem = 32.0),
            endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
            volumeMounts = Seq(VolumeMount(Some("pst"), "pst1", false))
          )
        ),
        volumes = Seq(PersistentVolume(name = Some("pst"), persistent = PersistentVolumeInfo(size = 10L))),
        networks = Seq(HostNetwork),
        instances = 1,
        unreachableStrategy = state.UnreachableDisabled,
        upgradeStrategy = state.UpgradeStrategy(0.0, 0.0)
      )
      marathon16549.client.createPodV2(resident_pod_16549) should be(Created)
      val (resident_pod_16549_port, resident_pod_16549_address) = eventually {
        val status = marathon16549.client.status(resident_pod_16549.id)
        status should be(Stable)
        status.value.instances(0).containers(0).endpoints(0).allocatedHostPort should be('defined)
        val port = status.value.instances(0).containers(0).endpoints(0).allocatedHostPort.get
        (port, status.value.instances(0).networks(0).addresses(0))
      }

      Then(s"pod ${resident_pod_16549.id} can be queried on http://$resident_pod_16549_address:$resident_pod_16549_port")
      implicit val requestTimeout = 30.seconds
      eventually { AkkaHttpResponse.request(Get(s"http://$resident_pod_16549_address:$resident_pod_16549_port/pst1/foo")).futureValue.entityString should be("start\n") }

      Then("All apps from 1.4.9 and 1.5.15 are still running")
      marathon16549.client.tasks(app_149.id.toPath).value should contain theSameElementsAs (originalApp149Tasks)
      marathon16549.client.tasks(app_1515.id.toPath).value should contain theSameElementsAs (originalApp1515Tasks)

      // Pass upgrade to current
      When("Marathon is upgraded to the current version")
      marathon16549.stop().futureValue
      val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterUrl, zkUrl = zkUrl)
      marathonCurrent.start().futureValue
      (marathonCurrent.client.info.entityJson \ "version").as[String] should be(BuildInfo.version.toString)

      Then("All apps from 1.4.9 and 1.5.15 are still running")
      val originalApp149TaskIds = originalApp149Tasks.map(_.id)
      val originalApp1515TaskIds = originalApp1515Tasks.map(_.id)
      marathonCurrent.client.tasks(app_149.id.toPath).value.map(_.id) should contain theSameElementsAs (originalApp149TaskIds)
      marathonCurrent.client.tasks(app_1515.id.toPath).value.map(_.id) should contain theSameElementsAs (originalApp1515TaskIds)

      And("All apps from 1.4.9 and 1.5.15 are recovered and running again")
      eventually { marathonCurrent should have(runningTasksFor(app_149_fail.id.toPath, 1)) }
      marathonCurrent.client.tasks(app_149_fail.id.toPath).value should not contain theSameElementsAs(originalApp149FailedTasks)

      eventually { marathonCurrent should have(runningTasksFor(app_1515_fail.id.toPath, 1)) }
      marathonCurrent.client.tasks(app_1515_fail.id.toPath).value should not contain theSameElementsAs(originalApp1515FailedTasks)

      And("All pods from 1.6.549 are still running")
      eventually { marathonCurrent.client.status(resident_pod_16549.id) should be(Stable) }
      eventually { AkkaHttpResponse.request(Get(s"http://$resident_pod_16549_address:$resident_pod_16549_port/pst1/foo")).futureValue.entityString should be("start\n") }

      marathonCurrent.close()
    }
  }

  "upgrade from 1.6.549 to the latest" in {
    val zkUrl = s"$zkURLBase-to-latest"
    val marathon16549 = Marathon16549(marathon16549Artifact.marathonPackage, suiteName = s"$suiteName-1-6-549", mesosMasterUrl, zkUrl)

    // Start apps in 1.6.549
    Given("A Marathon 1.6.549 is running")
    marathon16549.start().futureValue
    (marathon16549.client.info.entityJson \ "version").as[String] should be("1.6.549")

    And("new running apps in Marathon 1.6.549")
    val app_16549_fail = appProxy(testBasePath / "app-16549-fail", "v1", instances = 1, healthCheck = None)
    marathon16549.client.createAppV2(app_16549_fail) should be(Created)

    val app_16549 = appProxy(testBasePath / "prod" / "db" / "app-16549", "v1", instances = 1, healthCheck = None)
    marathon16549.client.createAppV2(app_16549) should be(Created)

    patienceConfig
    eventually { marathon16549 should have (runningTasksFor(app_16549.id.toPath, 1)) }
    eventually { marathon16549 should have (runningTasksFor(app_16549_fail.id.toPath, 1)) }

    val originalApp16549Tasks: List[ITEnrichedTask] = marathon16549.client.tasks(app_16549.id.toPath).value
    val originalApp16549FailedTasks = marathon16549.client.tasks(app_16549_fail.id.toPath).value

    When("Marathon 1.6.549 is shut down")
    marathon16549.stop().futureValue

    AppMockFacade.suicideAll(originalApp16549FailedTasks)

    // Pass upgrade to current
    When("Marathon is upgraded to the current version")
    val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterUrl, zkUrl = zkUrl)
    marathonCurrent.start().futureValue
    (marathonCurrent.client.info.entityJson \ "version").as[String] should be(BuildInfo.version.toString)

    Then("All apps from 1.6.549 are still running")
    val originalTaskIds = originalApp16549Tasks.map(_.id)
    marathonCurrent.client.tasks(app_16549.id.toPath).value.map(_.id) should contain theSameElementsAs (originalTaskIds)

    And("All apps from 1.6.549 are recovered and running again")
    eventually { marathonCurrent should have(runningTasksFor(app_16549_fail.id.toPath, 1)) }

    marathonCurrent.close()
  }

  "resident app can be restarted after upgrade from 1.6.549" in {
    val zkUrl = s"$zkURLBase-resident-apps"
    val marathon16549 = Marathon16549(marathon16549Artifact.marathonPackage, suiteName = s"$suiteName-1-6-549", mesosMasterUrl, zkUrl)

    // Start apps in 1.6.549
    Given("A Marathon 1.6.549 is running")
    marathon16549.start().futureValue
    (marathon16549.client.info.entityJson \ "version").as[String] should be("1.6.549")

    And("new running apps in Marathon 1.6.549")
    val containerPath = "persistent-volume"
    val residentApp_16549 = residentApp(
      id = testBasePath / "resident-app-16549",
      containerPath = containerPath,
      cmd = s"""echo "data" >> $containerPath/data && sleep 1000""")
    marathon16549.client.createAppV2(residentApp_16549) should be(Created)

    patienceConfig
    eventually { marathon16549 should have (runningTasksFor(residentApp_16549.id.toPath, 1)) }
    val originalApp16549Tasks = marathon16549.client.tasks(residentApp_16549.id.toPath).value

    When("We restart the app")
    marathon16549.client.restartApp(residentApp_16549.id.toPath) should be(OK)

    Then("We have new running tasks")
    eventually {
      marathon16549.client.tasks(residentApp_16549.id.toPath).value should not contain theSameElementsAs(originalApp16549Tasks)
      marathon16549 should have (runningTasksFor(residentApp_16549.id.toPath, 1))
    }

    // Pass upgrade to current
    When("Marathon is upgraded to the current version")
    marathon16549.stop().futureValue
    val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterUrl, zkUrl = zkUrl)
    marathonCurrent.start().futureValue
    (marathonCurrent.client.info.entityJson \ "version").as[String] should be(BuildInfo.version.toString)

    Then("All apps from 1.6.549 are still running")
    marathonCurrent should have (runningTasksFor(residentApp_16549.id.toPath, 1))
    val restartedApp16549Tasks = marathonCurrent.client.tasks(residentApp_16549.id.toPath).value

    When("We restart the app again")
    marathonCurrent.client.restartApp(residentApp_16549.id.toPath) should be(OK)

    Then("We have new running tasks")
    eventually {
      marathonCurrent.client.tasks(residentApp_16549.id.toPath).value should not contain theSameElementsAs(restartedApp16549Tasks)
      marathonCurrent should have (runningTasksFor(residentApp_16549.id.toPath, 1))
    }

    marathonCurrent.close()
  }

  /**
    * Scala [[HavePropertyMatcher]] that checks that numberOfTasks are in running state for app appId on given Marathon.
    *
    * Do not use the class directly but [[UpgradeIntegrationTest.runningTasksFor]]:
    *
    * {{{
    *   marathon149 should have(runningTasksFor(app_149.id.toPath, 2))
    * }}}
    *
    * @param appId The app the is checked for running tasks.
    * @param numberOfTasks The number of tasks that should be running.
    */
  class RunningTasksMatcher(appId: PathId, numberOfTasks: Int) extends HavePropertyMatcher[BaseMarathon, List[ITEnrichedTask]] {
    def apply(marathon: BaseMarathon): HavePropertyMatchResult[List[ITEnrichedTask]] = {
      val tasks = marathon.client.tasks(appId).value
      val notRunningTasks = tasks.filter(_.state != "TASK_RUNNING")
      val matches = tasks.size == numberOfTasks && notRunningTasks.size == 0
      HavePropertyMatchResult(matches, "runningTasks", List.empty, notRunningTasks)
    }
  }

  def runningTasksFor(appId: PathId, numberOfTasks: Int) = new RunningTasksMatcher(appId, numberOfTasks)

  override val testBasePath = PathId("/")
  override val healthCheckPort: Int = 0
}
