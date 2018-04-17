package mesosphere.marathon
package integration

import java.io.File
import java.net.URL
import java.nio.file.{ Files, Paths }

import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.stream.Materializer
import mesosphere.marathon.core.pod.{ HostNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.integration.facades.{ ITEnrichedTask, MarathonFacade }
import mesosphere.{ AkkaIntegrationTest, WhenEnvSet }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.marathon.state._
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.{ HavePropertyMatchResult, HavePropertyMatcher }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.sys.process.Process

/**
  * This integration test starts older Marathon versions one after another and finishes this upgrade procedure with the
  * current build. In each step we verfiy that all apps are still up and running.
  */
@IntegrationTest
class PodDeployIntegrationTest extends AkkaIntegrationTest with MesosClusterTest with ZookeeperServerTest with MarathonAppFixtures with Eventually {

  import PathId._

  val zkURL = s"zk://${zkServer.connectUri}/marathon-$suiteName"

  val marathon16322 = Marathon16322(suiteName = s"$suiteName-1-6-322", mesosMasterUrl, zkURL)
  val marathonCurrent = LocalMarathon(suiteName = s"$suiteName-current", masterUrl = mesosMasterUrl, zkUrl = zkURL)

  // Configure Mesos to provide the Mesos containerizer with Docker image support.
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    isolation = Some("filesystem/linux,docker/runtime"),
    imageProviders = Some("docker"))

  override def beforeAll(): Unit = {
    super.beforeAll()

    // Download Marathon releases
    marathon16322.downloadAndExtract()

    marathon16322.marathonPackage.deleteOnExit()
  }

  trait MarathonDownload {

    val projectDir = sys.props.getOrElse("user.dir", ".")
    val marathonPackage: File
    val tarballName: String
    val downloadURL: URL

    def downloadAndExtract() = {
      val tarball = new File(marathonPackage, tarballName)
      if (!Files.exists(tarball.toPath)) {
        logger.info(s"Downloading $tarballName to ${tarball.getCanonicalPath}")
        FileUtils.copyURLToFile(downloadURL, tarball)
        IO.extractTGZip(tarball, marathonPackage)
      }
    }
  }

  case class Marathon149(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon with MarathonDownload {

    override val marathonPackage = Files.createDirectories(Paths.get(projectDir, "marathon-1.4.9")).toFile
    override val tarballName = "marathon-1.4.9.tgz"
    override val downloadURL = new URL("https://downloads.mesosphere.com/marathon/releases/1.4.9/marathon-1.4.9.tgz")

    override val processBuilder = {
      val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
      val jar = new File(marathonPackage, "marathon-1.4.9/target/scala-2.11/marathon-assembly-1.4.9.jar").getCanonicalPath
      val cmd = Seq(java, "-Xmx1024m", "-Xms256m", "-XX:+UseConcMarkSweepGC", "-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName", "-client", "-jar", jar) ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon156(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon with MarathonDownload {

    override val marathonPackage = Files.createDirectories(Paths.get(projectDir, "marathon-1.5.6")).toFile
    override val tarballName = "marathon-1.5.6.tgz"
    override val downloadURL = new URL("https://downloads.mesosphere.com/marathon/releases/1.5.6/marathon-1.5.6.tgz")

    override val processBuilder = {
      val bin = new File(marathonPackage, "marathon-1.5.6/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName") ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  case class Marathon16322(suiteName: String, masterUrl: String, zkUrl: String)(
      implicit
      val system: ActorSystem, val mat: Materializer, val ctx: ExecutionContext, val scheduler: Scheduler) extends BaseMarathon with MarathonDownload {

    override val marathonPackage = Files.createDirectories(Paths.get(projectDir, "marathon-1.6.322")).toFile
    override val tarballName = "marathon-1.6.322.tgz"
    override val downloadURL = new URL("https://downloads.mesosphere.com/marathon/releases/1.6.322/marathon-1.6.322-2bf46b341.tgz")

    override val processBuilder = {
      val bin = new File(marathonPackage, "marathon-1.6.322-2bf46b341/bin/marathon").getCanonicalPath
      val cmd = Seq("bash", bin, "-J-Xmx1024m", "-J-Xms256m", "-J-XX:+UseConcMarkSweepGC", "-J-XX:ConcGCThreads=2") ++ akkaJvmArgs ++
        Seq(s"-DmarathonUUID=$uuid -DtestSuite=$suiteName") ++ args
      Process(cmd, workDir, sys.env.toSeq: _*)
    }
  }

  "Ephemeral and persistent apps and pods" should {
    "survive an upgrade cycle" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {

      // Pass upgrade to 1.6.322
      Given("Marathon 1.6.322 is running")
      marathon16322.start().futureValue
      (marathon16322.client.info.entityJson \ "version").as[String] should be("1.6.322")

      And("new pods in Marathon 1.6.322 are added")
      val resident_pod_16322 = residentPodWithService("resident-pod-16322")
      marathon16322.client.createPodV2(resident_pod_16322) should be(Created)
      val (resident_pod_16322_port, resident_pod_16322_address) = eventually { should_be_stable(marathon16322.client, resident_pod_16322.id) }

      val resident_pod_16322_fail = residentPodWithService("resident-pod-16322-fail")
      marathon16322.client.createPodV2(resident_pod_16322_fail) should be(Created)
      val (resident_pod_16322_port_fail, resident_pod_16322_address_fail) = eventually { should_be_stable(marathon16322.client, resident_pod_16322_fail.id) }

      val traceProcesses = strace("resident-pod-16322") ++ strace("resident-pod-16322-fail")

      Then(s"pods ${resident_pod_16322.id} and ${resident_pod_16322_fail.id} can be queried")
      implicit val requestTimeout = 30.seconds
      eventually {
        AkkaHttpResponse.request(Get(s"http://$resident_pod_16322_address:$resident_pod_16322_port/pst1/foo")).futureValue.entityString should be("start resident-pod-16322\n")
      }
      eventually {
        AkkaHttpResponse.request(Get(s"http://$resident_pod_16322_address_fail:$resident_pod_16322_port_fail/pst1/foo")).futureValue.entityString should be("start resident-pod-16322-fail\n")
      }

      When("Marathon 1.6.322 is shut down")
      marathon16322.stop().futureValue

      And(s"Pod ${resident_pod_16322_fail.id} fails")
      killTask("resident-pod-16322-fail")

      // Pass upgrade to current
      And("Marathon is upgraded to the current version")
      marathonCurrent.start().futureValue
      (marathonCurrent.client.info.entityJson \ "version").as[String] should be("1.6.0-SNAPSHOT")

      And("All pods from 1.6.322 are still running")
      eventually { marathonCurrent.client.status(resident_pod_16322.id) should be(Stable) }
      eventually {
        AkkaHttpResponse.request(Get(s"http://$resident_pod_16322_address:$resident_pod_16322_port/pst1/foo")).futureValue.entityString should be("start resident-pod-16322\n")
      }

      val (resident_pod_16322_port_recovered, resident_pod_16322_address_recovered) = eventually { should_be_stable(marathonCurrent.client, resident_pod_16322_fail.id) }
      val expected_content = "start resident-pod-16322-fail\nstart resident-pod-16322-fail\n"
      eventually {
        AkkaHttpResponse.request(Get(s"http://$resident_pod_16322_address_recovered:$resident_pod_16322_port_recovered/pst1/foo")).futureValue.entityString should be(expected_content)
      }

      marathonCurrent.close()
      mesosCluster.close()
    }
  }

  def killTask(runSpecName: String): Unit = {
    val pidPattern = """([^\s]+)\s+([^\s]+)\s+.*""".r
    val pids = Process("ps aux").!!.split("\n").filter { process =>
      process.contains("app_mock") && process.contains(runSpecName)
    }.collect {
      case pidPattern(_, pid) => pid
    }

    Process(s"kill -9 ${pids.mkString(" ")}").!
    logger.info(s"Killed tasks of run spec $runSpecName with PIDs ${pids.mkString(" ")}")
  }

  /**
    * straces all tasks belonging to run spec given by its name.
    * @param runSpecName
    */
  def strace(runSpecName: String): Array[Process] = {
    val pidPattern = """([^\s]+)\s+([^\s]+)\s+.*""".r
    val pids = Process("ps aux").!!.split("\n").filter { process =>
      process.contains("app_mock") && process.contains(runSpecName)
    }.collect {
      case pidPattern(_, pid) => pid
    }

    if (pids.isEmpty) {
      logger.info(s"Nothing to trace for $runSpecName!")
      return Array.empty
    }

    val traces = pids.map{ pid =>
      logger.info(s"strace $pid from $runSpecName")
      Process(s"sudo strace -p $pid").run(ProcessOutputToLogStream(s"strace-$runSpecName-$pid"))
    }
    traces
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

  /**
    * Define a Pod with a persistent volume and a simple http service.
    *
    * @param id Id of the pod run spec.
    * @return
    */
  def residentPodWithService(id: String) = {
    val projectDir = sys.props.getOrElse("user.dir", ".")

    val now = Timestamp.now()
    val cmd = s"cd $$MESOS_SANDBOX && echo 'start $id' >> pst1/foo && strace -TtttfFvx -s 256 -v -o pst1/$id-$now.trace src/app_mock.py $$ENDPOINT_TASK1 $id $now http://www.example.com"
    PodDefinition(
      id = testBasePath / id,
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand(cmd))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
          volumeMounts = Seq(
            VolumeMount(Some("appmocksrc"), "src", true),
            VolumeMount(Some("pst"), "pst1", true)
          )
        )
      ),
      volumes = Seq(
        HostVolume(Some("appmocksrc"), s"$projectDir/src/test/python"),
        PersistentVolume(name = Some("pst"), persistent = PersistentVolumeInfo(size = 10L))
      ),
      networks = Seq(HostNetwork),
      instances = 1,
      unreachableStrategy = state.UnreachableDisabled,
      upgradeStrategy = state.UpgradeStrategy(0.0, 0.0)
    )
  }

  /**
    * Asserts that pod with given id is stable and has a port defined.
    *
    * @param client Client to Marathon.
    * @param podId The id of the pod to check.
    * @return A tuple of port number and address of the service.
    */
  def should_be_stable(client: MarathonFacade, podId: PathId): (Int, String) = {
    val status = client.status(podId)
    status should be(Stable)
    status.value.instances(0).containers(0).endpoints(0).allocatedHostPort should be('defined)
    val port = status.value.instances(0).containers(0).endpoints(0).allocatedHostPort.get
    (port, status.value.instances(0).networks(0).addresses(0))
  }

  def runningTasksFor(appId: PathId, numberOfTasks: Int) = new RunningTasksMatcher(appId, numberOfTasks)

  override val testBasePath = PathId("/")
  override val healthCheckPort: Int = 0
}
