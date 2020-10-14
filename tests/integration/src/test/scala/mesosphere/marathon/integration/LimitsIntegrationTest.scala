package mesosphere.marathon.integration

import java.io.File

import com.mesosphere.utils.mesos.MesosAgentConfig
import mesosphere.marathon.integration.facades.AppMockFacade
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.{AkkaIntegrationTest, WhenLinux}
import org.apache.commons.io.FileUtils
import org.scalatest.Inside
import play.api.libs.json.Json

class LimitsIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {

  case class CgroupInfo(memory: Map[String, String], cpu: Map[String, String])
  implicit val cgroupInfoFormat = Json.format[CgroupInfo]
  val projectDir: String = sys.props.getOrElse("user.dir", ".")
  override lazy val agentConfig = MesosAgentConfig(
    launcher = "linux",
    containerizers = "mesos",
    isolation = Some("filesystem/linux,cgroups/cpu,cgroups/mem"),
    cgroupsEnableCfs = true
  )

  override def afterEach(): Unit = {
    marathon.deleteRoot(true)
    super.afterEach()
  }

  def deployApp(app: raml.App): CallbackEvent = {
    val createResult = marathon.createAppV2(app)
    createResult should be(Created)
    waitForDeployment(createResult)
  }

  def deployPod(pod: raml.Pod) = {
    val createResult = marathon.createPodV2(pod)
    createResult should be(Created)
    waitForDeployment(createResult)
  }

  def getPodInstanceCgroupInfo(podId: AbsolutePathId): CgroupInfo = {
    eventually {
      marathon.status(podId) should be(Stable)
      val status = marathon.status(podId).value
      val host = inside(status.instances.flatMap(_.agentHostname)) {
        case Seq(host) => host
      }
      val port = inside(status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))) {
        case Seq(port) => port
      }

      val facade = AppMockFacade(host, port)

      val groupJson = facade.get("/cgroup").futureValue.entityString
      Json.parse(groupJson).as[CgroupInfo]
    }
  }

  def getTaskCgroupInfo(appId: AbsolutePathId): CgroupInfo = {
    eventually {
      val tasks = marathon.tasks(appId).value
      tasks.length shouldBe 1
      val task = tasks.head
      task.state shouldBe "TASK_RUNNING"
      val host = task.host
      val port = task.ports.get.head

      val facade = AppMockFacade(host, port)

      val groupJson = facade.get("/cgroup").futureValue.entityString
      Json.parse(groupJson).as[CgroupInfo]
    }
  }

  def logCgroupInfo(desc: String, cgroupInfo: CgroupInfo) = {
    val all = (cgroupInfo.cpu.map { case (k, v) => s"cpu:${k}" -> v } ++ cgroupInfo.memory.map { case (k, v) => s"mem:${k}" -> v })

    all.toSeq.sorted.foreach {
      case (k, v) =>
        println(s"${desc}\t${k}\t${v.split("\n").headOption.getOrElse("")}")
    }
  }

  lazy val totalCpuShares: Int = {
    FileUtils.readFileToString(new File("/sys/fs/cgroup/cpu/cpu.shares"), java.nio.charset.StandardCharsets.US_ASCII).trim.toInt
  }

  "launch apps with and without limits specified for memory and cpus" taggedAs WhenLinux in {
    val unlimitedAppId = testBasePath / "unlimited-app"
    val limitedAppId = testBasePath / "limited-app"
    val limitsUnsetAppId = testBasePath / "limits-unset-app"

    val unlimitedApp = raml.App(
      id = unlimitedAppId.toString,
      container = Some(raml.Container(`type` = raml.EngineType.Mesos)),
      cmd = Some(appMockCmd(unlimitedAppId, "1")),
      cpus = 0.1,
      mem = 32,
      executorResources = Some(raml.ExecutorResources(cpus = 0.1, mem = 32)),
      resourceLimits = Some(
        raml.ResourceLimits(cpus = Some(raml.ResourceLimitUnlimited("unlimited")), mem = Some(raml.ResourceLimitUnlimited("unlimited")))
      )
    )

    val limitedApp = raml.App(
      id = limitedAppId.toString,
      container = Some(raml.Container(`type` = raml.EngineType.Mesos)),
      cmd = Some(appMockCmd(unlimitedAppId, "1")),
      cpus = 0.1,
      mem = 32,
      executorResources = Some(raml.ExecutorResources(cpus = 0.1, mem = 32)),
      resourceLimits = Some(raml.ResourceLimits(cpus = Some(raml.ResourceLimitNumber(1)), mem = Some(raml.ResourceLimitNumber(128))))
    )

    val limitsUnsetApp = raml.App(
      id = limitsUnsetAppId.toString,
      container = Some(raml.Container(`type` = raml.EngineType.Mesos)),
      cmd = Some(appMockCmd(unlimitedAppId, "1")),
      cpus = 0.1,
      mem = 32,
      executorResources = Some(raml.ExecutorResources(cpus = 0.1, mem = 32)),
      resourceLimits = None
    )

    deployApp(unlimitedApp)
    deployApp(limitsUnsetApp)
    deployApp(limitedApp)

    val unlimitedCgroupInfo = getTaskCgroupInfo(unlimitedAppId)
    val limitedCgroupInfo = getTaskCgroupInfo(limitedAppId)
    val limitsUnsetCgroupInfo = getTaskCgroupInfo(limitsUnsetAppId)

    logCgroupInfo("app unlimitedCgroupInfo", unlimitedCgroupInfo)
    logCgroupInfo("app limitedCgroupInfo", limitedCgroupInfo)
    logCgroupInfo("app limitsUnsetCgroupInfo", limitsUnsetCgroupInfo)

    // assert cpu shares are set the same for all apps
    Then("the unlimited app should have no quota defined for CPU")
    unlimitedCgroupInfo.cpu("cpu.cfs_quota_us").toInt shouldBe -1

    And("the quota for the app with limits set is greater than the quota with the default limits")
    limitedCgroupInfo.cpu("cpu.cfs_quota_us").toInt shouldBe >(limitsUnsetCgroupInfo.cpu("cpu.cfs_quota_us").toInt)

    And("the unlimited app should have a substantially higher memory limit than the app with limits specified")
    unlimitedCgroupInfo.memory("memory.limit_in_bytes").toLong shouldBe >(limitedCgroupInfo.memory("memory.limit_in_bytes").toLong)

    And("the app with limits specified should have a a higher memory limit than the app with limits unspecified")
    limitedCgroupInfo.memory("memory.limit_in_bytes").toLong shouldBe >(limitsUnsetCgroupInfo.memory("memory.limit_in_bytes").toLong)
  }

  "launch pods with and without limits specified for memory and cpus" taggedAs WhenLinux in {
    val unlimitedPodId = testBasePath / "unlimited-pod"
    val limitedPodId = testBasePath / "limited-pod"
    val limitsUnsetPodId = testBasePath / "limits-unset-pod"

    val unlimitedPod = raml.Pod(
      id = unlimitedPodId.toString,
      networks = Seq(raml.Network(mode = raml.NetworkMode.Host)),
      executorResources = Some(raml.ExecutorResources(cpus = 0.1, mem = 32)),
      containers = Seq(
        raml.PodContainer(
          name = "primary",
          exec = Some(
            raml.MesosExec(
              command = raml.ShellCommand(appMockCmd(unlimitedPodId, port = "$ENDPOINT_HTTP", versionId = "1"))
            )
          ),
          resources = raml.Resources(
            cpus = 0.1,
            mem = 32
          ),
          endpoints = Seq(raml.Endpoint(name = "http", hostPort = Some(0))),
          resourceLimits = Some(
            raml.ResourceLimits(cpus = Some(raml.ResourceLimitUnlimited("unlimited")), mem = Some(raml.ResourceLimitUnlimited("unlimited")))
          )
        )
      )
    )

    val limitedPod = raml.Pod(
      id = limitedPodId.toString,
      networks = Seq(raml.Network(mode = raml.NetworkMode.Host)),
      executorResources = Some(raml.ExecutorResources(cpus = 0.1, mem = 32)),
      containers = Seq(
        raml.PodContainer(
          name = "primary",
          exec = Some(
            raml.MesosExec(
              command = raml.ShellCommand(appMockCmd(unlimitedPodId, port = "$ENDPOINT_HTTP", versionId = "1"))
            )
          ),
          resources = raml.Resources(
            cpus = 0.1,
            mem = 32
          ),
          endpoints = Seq(raml.Endpoint(name = "http", hostPort = Some(0))),
          resourceLimits = Some(raml.ResourceLimits(cpus = Some(raml.ResourceLimitNumber(1)), mem = Some(raml.ResourceLimitNumber(128))))
        )
      )
    )

    val limitsUnsetPod = raml.Pod(
      id = limitsUnsetPodId.toString,
      networks = Seq(raml.Network(mode = raml.NetworkMode.Host)),
      executorResources = Some(raml.ExecutorResources(cpus = 0.1, mem = 32)),
      containers = Seq(
        raml.PodContainer(
          name = "primary",
          exec = Some(
            raml.MesosExec(
              command = raml.ShellCommand(appMockCmd(unlimitedPodId, port = "$ENDPOINT_HTTP", versionId = "1"))
            )
          ),
          resources = raml.Resources(
            cpus = 0.1,
            mem = 32
          ),
          endpoints = Seq(raml.Endpoint(name = "http", hostPort = Some(0))),
          resourceLimits = None
        )
      )
    )

    deployPod(unlimitedPod)
    deployPod(limitsUnsetPod)
    deployPod(limitedPod)

    val unlimitedCgroupInfo = getPodInstanceCgroupInfo(unlimitedPodId)
    val limitedCgroupInfo = getPodInstanceCgroupInfo(limitedPodId)
    val limitsUnsetCgroupInfo = getPodInstanceCgroupInfo(limitsUnsetPodId)

    logCgroupInfo("pod - unlimitedCgroupInfo", unlimitedCgroupInfo)
    logCgroupInfo("pod - limitedCgroupInfo", limitedCgroupInfo)
    logCgroupInfo("pod - limitsUnsetCgroupInfo", limitsUnsetCgroupInfo)

    // assert cpu shares are set the same for all pods
    Then("the unlimited pod should have no quota defined for CPU")
    unlimitedCgroupInfo.cpu("cpu.cfs_quota_us").toInt shouldBe -1

    And("the quota for the pod with limits set is greater than the quota with the default limits")
    limitedCgroupInfo.cpu("cpu.cfs_quota_us").toInt shouldBe >(limitsUnsetCgroupInfo.cpu("cpu.cfs_quota_us").toInt)

    And("the unlimited pod should have a substantially higher memory limit than the pod with limits specified")
    unlimitedCgroupInfo.memory("memory.limit_in_bytes").toLong shouldBe >(limitedCgroupInfo.memory("memory.limit_in_bytes").toLong)

    And("the pod with limits specified should have a a higher memory limit than the pod with limits unspecified")
    limitedCgroupInfo.memory("memory.limit_in_bytes").toLong shouldBe >(limitsUnsetCgroupInfo.memory("memory.limit_in_bytes").toLong)
  }
}
