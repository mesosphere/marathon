package mesosphere.marathon
package integration

import java.io.File
import java.net.URL

import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.stream.Materializer
import com.mesosphere.utils.http.AkkaHttpResponse
import com.mesosphere.utils.mesos.{MesosAgentConfig, MesosClusterTest}
import com.mesosphere.utils.zookeeper.ZookeeperServerTest
import mesosphere.marathon.core.pod.{HostNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.integration.facades.{AppMockFacade, ITEnrichedTask}
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.marathon.raml.PodState
import mesosphere.marathon.state.{AbsolutePathId, PersistentVolume, PersistentVolumeInfo, VolumeMount}
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build. In each step we verify that all apps are still up and running.
  */
class UpgradeIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with MarathonAppFixtures with Eventually {

  val zkURLBase = s"zk://${zkserver.connectUrl}/marathon-$suiteName"

  val marathonMinus3Artifact = MarathonArtifact(SemVer(1, 7, 216, Some("9e2a9b579")))
  val marathonMinus2Artifact = MarathonArtifact(SemVer(1, 8, 222, Some("86475ddac")))
  val marathonMinus1Artifact = MarathonArtifact(SemVer(1, 9, 124, Some("e04033c6f")))

  //   Configure Mesos to provide the Mesos containerizer with Docker image support.
  override lazy val agentConfig = MesosAgentConfig(
    launcher = "linux",
    isolation = Some("filesystem/linux,docker/runtime"),
    imageProviders = Some("docker"))

  override def beforeAll(): Unit = {

    require(marathonMinus3Artifact.version.minor == BuildInfo.version.minor - 3, s"expected Marathon n-3 to have minor version ${BuildInfo.version.minor - 3}, but instead was ${marathonMinus3Artifact.version.minor}")
    require(marathonMinus2Artifact.version.minor == BuildInfo.version.minor - 2, s"expected Marathon n-2 to have minor version ${BuildInfo.version.minor - 2}, but instead was ${marathonMinus2Artifact.version.minor}")
    require(marathonMinus1Artifact.version.minor == BuildInfo.version.minor - 1, s"expected Marathon n-1 to have minor version ${BuildInfo.version.minor - 1}, but instead was ${marathonMinus1Artifact.version.minor}")

    // Download Marathon releases
    marathonMinus3Artifact.downloadAndExtract()
    marathonMinus2Artifact.downloadAndExtract()
    marathonMinus1Artifact.downloadAndExtract()
    super.beforeAll()
  }

  case class MarathonArtifact(version: SemVer) {
    private val targetFolder: File = new File("target/universal").getAbsoluteFile
    val tarballName = s"marathon-${version}.tgz"
    val tarball = new File(targetFolder, tarballName)

    val downloadURL: URL = new URL(s"https://downloads.mesosphere.io/marathon/builds/${version}/marathon-${version}.tgz")

    val marathonBaseFolder = new File(targetFolder, s"marathon-${version}")

    def downloadAndExtract() = {
      logger.info(s"Downloading $tarballName to ${tarball.getCanonicalPath}")
      if (!tarball.isFile) {
        targetFolder.mkdirs()
        FileUtils.copyURLToFile(downloadURL, tarball)
      }
      if (!marathonBaseFolder.isDirectory) {
        targetFolder.mkdirs()
        IO.extractTGZip(tarball, targetFolder)
      }
    }
  }

  case class PackagedMarathon(marathonBaseFolder: File, suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon {

    override val processBuilder = {
      val bin = new File(marathonBaseFolder, "bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName") ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  def versionWithoutCommit(version: SemVer): String =
    version.copy(commit = None).toString

  "Ephemeral and persistent apps and pods" should {
    "survive an upgrade cycle" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {

      val zkUrl = s"$zkURLBase-upgrade-cycle"

      // Start apps in initial version
      Given(s"A Marathon n-3 is running (${marathonMinus3Artifact.version})")
      val marathonMinus3 = PackagedMarathon(marathonMinus3Artifact.marathonBaseFolder, suiteName = s"$suiteName-n-minus-3", mesosMasterZkUrl, zkUrl)
      marathonMinus3.start().futureValue
      (marathonMinus3.client.info.entityJson \ "version").as[String] should be(versionWithoutCommit(marathonMinus3Artifact.version))

      And(s"new running apps in Marathon n-3 (${marathonMinus3Artifact.version})")
      val app_nm3_fail = appProxy(testBasePath / "app-nm3-fail", "v1", instances = 1, healthCheck = None)
      marathonMinus3.client.createAppV2(app_nm3_fail) should be(Created)

      val app_nm3 = appProxy(testBasePath / "app-nm3", "v1", instances = 1, healthCheck = None)
      marathonMinus3.client.createAppV2(app_nm3) should be(Created)

      patienceConfig
      eventually { marathonMinus3 should have (runningTasksFor(AbsolutePathId(app_nm3.id), 1)) }
      eventually { marathonMinus3 should have (runningTasksFor(AbsolutePathId(app_nm3_fail.id), 1)) }

      val originalAppNm3Tasks: List[ITEnrichedTask] = marathonMinus3.client.tasks(AbsolutePathId(app_nm3.id)).value
      val originalAppNm3FailedTasks: List[ITEnrichedTask] = marathonMinus3.client.tasks(AbsolutePathId(app_nm3_fail.id)).value

      When(s"Marathon n-3 is shut down (${marathonMinus3Artifact.version})")
      marathonMinus3.stop().futureValue

      And(s"App ${app_nm3_fail.id} fails")
      AppMockFacade.suicideAll(originalAppNm3FailedTasks)

      // Pass upgrade to n-2
      And(s"Marathon is upgraded to n-2 (${marathonMinus2Artifact.version})")
      val marathonMinus2 = PackagedMarathon(marathonMinus2Artifact.marathonBaseFolder, s"$suiteName-n-minus-2", mesosMasterZkUrl, zkUrl)
      marathonMinus2.start().futureValue
      (marathonMinus2.client.info.entityJson \ "version").as[String] should be(versionWithoutCommit(marathonMinus2Artifact.version))

      And("new apps in Marathon n-2 are added")
      val app_nm2 = appProxy(testBasePath / "app-nm2", "v1", instances = 1, healthCheck = None)
      marathonMinus2.client.createAppV2(app_nm2) should be(Created)

      val app_nm2_fail = appProxy(testBasePath / "app-nm2-fail", "v1", instances = 1, healthCheck = None)
      marathonMinus2.client.createAppV2(app_nm2_fail) should be(Created)

      Then(s"All apps from ${marathonMinus2Artifact.version} are running")
      eventually { marathonMinus2 should have (runningTasksFor(AbsolutePathId(app_nm2.id), 1)) }
      eventually { marathonMinus2 should have (runningTasksFor(AbsolutePathId(app_nm2_fail.id), 1)) }

      val originalAppNm2Tasks: List[ITEnrichedTask] = marathonMinus2.client.tasks(AbsolutePathId(app_nm2.id)).value
      val originalAppNm2FailedTasks: List[ITEnrichedTask] = marathonMinus2.client.tasks(AbsolutePathId(app_nm2_fail.id)).value

      And(s"All apps from n-2 are still running (${marathonMinus2Artifact.version})")
      marathonMinus2.client.tasks(AbsolutePathId(app_nm3.id)).value should contain theSameElementsAs (originalAppNm3Tasks)

      When(s"Marathon n-2 is shut down (${marathonMinus2Artifact.version})")
      marathonMinus2.stop().futureValue

      And(s"App ${app_nm2_fail.id} fails")
      AppMockFacade.suicideAll(originalAppNm2FailedTasks)

      And(s"Marathon is upgraded to n-1")
      val marathonMinus1 = PackagedMarathon(marathonMinus1Artifact.marathonBaseFolder, s"$suiteName-n-minus-1", mesosMasterZkUrl, zkUrl)
      marathonMinus1.start().futureValue
      (marathonMinus1.client.info.entityJson \ "version").as[String] should be(versionWithoutCommit(marathonMinus1Artifact.version))

      And(s"new pods in Marathon n-1 are added (${marathonMinus1Artifact.version})")
      val resident_pod_nm1 = PodDefinition(
        id = testBasePath / "resident-pod-nm1",
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
      marathonMinus1.client.createPodV2(resident_pod_nm1) should be(Created)
      val (resident_pod_nm1_port, resident_pod_nm1_address) = eventually {
        val status = marathonMinus1.client.status18(resident_pod_nm1.id)
        status.value.status shouldBe PodState.Stable
        status.value.instances(0).containers(0).endpoints(0).allocatedHostPort should be('defined)
        val port = status.value.instances(0).containers(0).endpoints(0).allocatedHostPort.get
        (port, status.value.instances(0).networks(0).addresses(0))
      }

      Then(s"pod ${resident_pod_nm1.id} can be queried on http://$resident_pod_nm1_address:$resident_pod_nm1_port")
      implicit val requestTimeout = 30.seconds
      eventually { AkkaHttpResponse.request(Get(s"http://$resident_pod_nm1_address:$resident_pod_nm1_port/pst1/foo")).futureValue.entityString should be("start\n") }

      Then(s"All apps from n-3 and n-2 are still running (${marathonMinus3Artifact.version} and ${marathonMinus2Artifact.version})")
      marathonMinus1.client.tasks(AbsolutePathId(app_nm3.id)).value should contain theSameElementsAs (originalAppNm3Tasks)
      marathonMinus1.client.tasks(AbsolutePathId(app_nm2.id)).value should contain theSameElementsAs (originalAppNm2Tasks)

      // Pass upgrade to current
      When("Marathon is upgraded to the current version")
      marathonMinus1.stop().futureValue
      val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterZkUrl, zkUrl = zkUrl)
      marathonCurrent.start().futureValue
      (marathonCurrent.client.info.entityJson \ "version").as[String] should be(BuildInfo.version.toString)

      Then(s"All apps from n-3 and n-2 are still running (${marathonMinus3Artifact.version} and ${marathonMinus2Artifact.version})")
      val originalAppnm3TaskIds = originalAppNm3Tasks.map(_.id)
      val originalAppnm2TaskIds = originalAppNm2Tasks.map(_.id)
      marathonCurrent.client.tasks(AbsolutePathId(app_nm3.id)).value.map(_.id) should contain theSameElementsAs (originalAppnm3TaskIds)
      marathonCurrent.client.tasks(AbsolutePathId(app_nm2.id)).value.map(_.id) should contain theSameElementsAs (originalAppnm2TaskIds)

      And(s"All apps from n-3 and n-2 are recovered and running again (${marathonMinus3Artifact.version} and ${marathonMinus2Artifact.version})")
      eventually { marathonCurrent should have(runningTasksFor(AbsolutePathId(app_nm3_fail.id), 1)) }
      marathonCurrent.client.tasks(AbsolutePathId(app_nm3_fail.id)).value should not contain theSameElementsAs(originalAppNm3FailedTasks)

      eventually { marathonCurrent should have(runningTasksFor(AbsolutePathId(app_nm2_fail.id), 1)) }
      marathonCurrent.client.tasks(AbsolutePathId(app_nm2_fail.id)).value should not contain theSameElementsAs(originalAppNm2FailedTasks)

      And(s"All pods from n-1 are still running (${marathonMinus1Artifact.version})")
      eventually { marathonCurrent.client.status(resident_pod_nm1.id) should be(Stable) }
      eventually { AkkaHttpResponse.request(Get(s"http://$resident_pod_nm1_address:$resident_pod_nm1_port/pst1/foo")).futureValue.entityString should be("start\n") }

      marathonCurrent.close()
    }
  }

  "upgrade from n-3 directly to the latest" in {
    val zkUrl = s"$zkURLBase-to-latest"
    val marathonNm3 = PackagedMarathon(marathonMinus3Artifact.marathonBaseFolder, suiteName = s"$suiteName-n-minus-3", mesosMasterZkUrl, zkUrl)

    // Start apps in n-3
    Given(s"A Marathon n-3 is running (${marathonMinus3Artifact.version})")
    marathonNm3.start().futureValue
    (marathonNm3.client.info.entityJson \ "version").as[String] should be(versionWithoutCommit(marathonMinus3Artifact.version))

    And("new running apps in Marathon n-3")
    val app_nm3_fail = appProxy(testBasePath / "app-nm3-fail", "v1", instances = 1, healthCheck = None)
    marathonNm3.client.createAppV2(app_nm3_fail) should be(Created)

    val app_nm3 = appProxy(testBasePath / "prod" / "db" / "app-nm3", "v1", instances = 1, healthCheck = None)
    marathonNm3.client.createAppV2(app_nm3) should be(Created)

    patienceConfig
    eventually { marathonNm3 should have (runningTasksFor(AbsolutePathId(app_nm3.id), 1)) }
    eventually { marathonNm3 should have (runningTasksFor(AbsolutePathId(app_nm3_fail.id), 1)) }

    val originalAppNm3Tasks: List[ITEnrichedTask] = marathonNm3.client.tasks(AbsolutePathId(app_nm3.id)).value
    val originalAppNm3FailedTasks = marathonNm3.client.tasks(AbsolutePathId(app_nm3_fail.id)).value

    When("Marathon n-3 is shut down")
    marathonNm3.stop().futureValue

    AppMockFacade.suicideAll(originalAppNm3FailedTasks)

    // Pass upgrade to current
    When("Marathon is upgraded to the current version")
    val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterZkUrl, zkUrl = zkUrl)
    marathonCurrent.start().futureValue
    (marathonCurrent.client.info.entityJson \ "version").as[String] should be(BuildInfo.version.toString)

    Then("All apps from n-3 are still running")
    val originalTaskIds = originalAppNm3Tasks.map(_.id)
    marathonCurrent.client.tasks(AbsolutePathId(app_nm3.id)).value.map(_.id) should contain theSameElementsAs (originalTaskIds)

    And("All apps from n-3 are recovered and running again")
    eventually { marathonCurrent should have(runningTasksFor(AbsolutePathId(app_nm3_fail.id), 1)) }

    marathonCurrent.close()
  }

  "resident app can be restarted after upgrade from n-1" in {
    val zkUrl = s"$zkURLBase-resident-apps"
    val marathonnm1 = PackagedMarathon(marathonMinus1Artifact.marathonBaseFolder, suiteName = s"$suiteName-n-minus-1", mesosMasterZkUrl, zkUrl)

    Given(s"A Marathon n-1 is running (${marathonMinus1Artifact.version})")
    marathonnm1.start().futureValue
    (marathonnm1.client.info.entityJson \ "version").as[String] should be(versionWithoutCommit(marathonMinus1Artifact.version))

    And("new running apps in Marathon n-1")
    val containerPath = "persistent-volume"
    val residentApp_nm1 = residentApp(
      id = testBasePath / "resident-app-nm1",
      containerPath = containerPath,
      cmd = s"""echo "data" >> $containerPath/data && sleep 1000""")
    marathonnm1.client.createAppV2(residentApp_nm1) should be(Created)

    patienceConfig
    eventually { marathonnm1 should have (runningTasksFor(AbsolutePathId(residentApp_nm1.id), 1)) }
    val originalAppnm1Tasks = marathonnm1.client.tasks(AbsolutePathId(residentApp_nm1.id)).value

    When("We restart the app")
    marathonnm1.client.restartApp(AbsolutePathId(residentApp_nm1.id)) should be(OK)

    Then("We have new running tasks")
    eventually {
      marathonnm1.client.tasks(AbsolutePathId(residentApp_nm1.id)).value should not contain theSameElementsAs(originalAppnm1Tasks)
      marathonnm1 should have (runningTasksFor(AbsolutePathId(residentApp_nm1.id), 1))
    }

    // Pass upgrade to current
    When("Marathon is upgraded to the current version")
    marathonnm1.stop().futureValue
    val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterZkUrl, zkUrl = zkUrl)
    marathonCurrent.start().futureValue
    (marathonCurrent.client.info.entityJson \ "version").as[String] should be(BuildInfo.version.toString)

    Then(s"All apps from n-1 are still running (${marathonMinus1Artifact.version}")
    marathonCurrent should have (runningTasksFor(AbsolutePathId(residentApp_nm1.id), 1))
    val restartedAppnm1Tasks = marathonCurrent.client.tasks(AbsolutePathId(residentApp_nm1.id)).value

    When("We restart the app again")
    marathonCurrent.client.restartApp(AbsolutePathId(residentApp_nm1.id)) should be(OK)

    Then("We have new running tasks")
    eventually {
      marathonCurrent.client.tasks(AbsolutePathId(residentApp_nm1.id)).value should not contain theSameElementsAs(restartedAppnm1Tasks)
      marathonCurrent should have (runningTasksFor(AbsolutePathId(residentApp_nm1.id), 1))
    }

    marathonCurrent.close()
  }

  /**
    * Scala [[HavePropertyMatcher]] that checks that numberOfTasks are in running state for app appId on given Marathon.
    *
    * Do not use the class directly but [[UpgradeIntegrationTest.runningTasksFor]]:
    *
    * {{{
    *   marathon17 should have(runningTasksFor(app_nm2.id.toPath, 2))
    * }}}
    *
    * @param appId The app the is checked for running tasks.
    * @param numberOfTasks The number of tasks that should be running.
    */
  class RunningTasksMatcher(appId: AbsolutePathId, numberOfTasks: Int) extends HavePropertyMatcher[BaseMarathon, List[ITEnrichedTask]] {
    def apply(marathon: BaseMarathon): HavePropertyMatchResult[List[ITEnrichedTask]] = {
      val tasks = marathon.client.tasks(appId).value
      val notRunningTasks = tasks.filter(_.state != "TASK_RUNNING")
      val matches = tasks.size == numberOfTasks && notRunningTasks.size == 0
      HavePropertyMatchResult(matches, "runningTasks", List.empty, notRunningTasks)
    }
  }

  def runningTasksFor(appId: AbsolutePathId, numberOfTasks: Int) = new RunningTasksMatcher(appId, numberOfTasks)

  override val testBasePath = AbsolutePathId("/")
  override val healthCheckPort: Int = 0
}
