package mesosphere.marathon
package integration

import mesosphere.marathon.core.health.{MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.pod.{HostNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.integration.facades.AppMockFacade
import mesosphere.marathon.integration.setup.{BaseMarathon, EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.raml.{Pod, Raml}
import mesosphere.marathon.state.{HostVolume, VolumeMount}
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}
import org.scalatest.time.{Seconds, Span}
import play.api.libs.json.Json

import scala.collection.immutable.Seq

class SharedMemoryIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  val projectDir: String = sys.props.getOrElse("user.dir", ".")
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    containerizers = "docker,mesos",
    isolation = Some("filesystem/linux,docker/runtime,namespaces/ipc"),
    imageProviders = Some("docker")
  )

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds), interval = Span(1, Seconds))

  "get correct shm size from pod" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("a pod with a single task and a volume")
    val projectDir = sys.props.getOrElse("user.dir", ".")
    val containerDir = "marathon"
    val id = testBasePath / "simple-pod-with-shm-setup"

    val shmSize = 11

    def appMockCommand(port: String) =
      s"""
         |echo APP PROXY $$MESOS_TASK_ID RUNNING; \\
         |$containerDir/python/app_mock.py $port $id v1 http://httpbin.org/anything
        """.stripMargin

    val pod = PodDefinition(
      id = id,
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK1")))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
          healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task1")), path = Some("/ping"))),
          volumeMounts = Seq(
            VolumeMount(Some("python"), s"$containerDir/python", true)
          ),
          linuxInfo = Some(state.LinuxInfo(
            seccomp = None,
            ipcInfo = Some(state.IPCInfo(
              ipcMode = state.IpcMode.Private,
              shmSize = Some(shmSize)
            ))
          ))
        )
      ),
      volumes = Seq(
        HostVolume(Some("python"), s"$projectDir/src/test/resources/python")
      ),
      unreachableStrategy = state.UnreachableDisabled,
      upgradeStrategy = state.UpgradeStrategy(0.0, 0.0),
      networks = Seq(HostNetwork),
      instances = 1,
      role = BaseMarathon.defaultRole
    )

    When("The pod is deployed")
    val createResult = marathon.createPodV2(pod)
    createResult should be(Created)
    waitForDeployment(createResult)
    val ipcInfo:String = eventually {
      marathon.status(pod.id) should be(Stable)
      val status = marathon.status(pod.id).value
      val hosts = status.instances.flatMap(_.agentHostname)
      hosts should have size (1)
      val ports = status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))
      ports should have size (1)
      val facade = AppMockFacade(hosts.head, ports.head)

      val ipcInfoString = facade.get(s"/ipcinfo").futureValue
      logger.info("IpcInfo is: " + ipcInfoString)
      ipcInfoString should include("=======")

      ipcInfoString
    }

    logger.info("IPCInfo is: " + ipcInfo)

    val shmFsSizeRegex = "tmpfs\\s+([0-9]+)\\s+[0-9]+\\s+[0-9]+\\s+[0-9]+%\\s+/dev/shm".r
    val shmFsSizeMatch = shmFsSizeRegex.findFirstMatchIn(ipcInfo)

    val shmSizeFromIpcInfo = shmFsSizeMatch.value.group(1)

    shmSizeFromIpcInfo should be("" + shmSize)

  }

  "check share parent shm works" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("a pod with a two tasks and shareParent ipcInfo")
    val projectDir = sys.props.getOrElse("user.dir", ".")
    val containerDir = "marathon"
    val id = testBasePath / "pod-with-shared-parent-shm-setup"

    val shmSize = 9

    def appMockCommand(port: String) =
      s"""
         |echo APP PROXY $$MESOS_TASK_ID RUNNING; \\
         |$containerDir/python/app_mock.py $port $id v1 http://httpbin.org/anything
        """.stripMargin

    val pod = PodDefinition(
      id = id,
      linuxInfo = Some(state.LinuxInfo(
        seccomp = None,
        ipcInfo = Some(state.IPCInfo(
          ipcMode = state.IpcMode.Private,
          shmSize = Some(shmSize)
        ))
      )),
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK1")))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task1", hostPort = Some(0))),
          healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task1")), path = Some("/ping"))),
          volumeMounts = Seq(
            VolumeMount(Some("python"), s"$containerDir/python", true)
          ),
          linuxInfo = Some(state.LinuxInfo(
            seccomp = None,
            ipcInfo = Some(state.IPCInfo(
              ipcMode = state.IpcMode.ShareParent,
              shmSize = None
            ))
          ))
        ),
        MesosContainer(
          name = "task2",
          exec = Some(raml.MesosExec(raml.ShellCommand(appMockCommand("$ENDPOINT_TASK2")))),
          resources = raml.Resources(cpus = 0.1, mem = 32.0),
          endpoints = Seq(raml.Endpoint(name = "task2", hostPort = Some(0))),
          healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("task2")), path = Some("/ping"))),
          volumeMounts = Seq(
            VolumeMount(Some("python"), s"$containerDir/python", true)
          ),
          linuxInfo = Some(state.LinuxInfo(
            seccomp = None,
            ipcInfo = Some(state.IPCInfo(
              ipcMode = state.IpcMode.ShareParent,
              shmSize = None
            ))
          ))
        )
      ),
      volumes = Seq(
        HostVolume(Some("python"), s"$projectDir/src/test/resources/python")
      ),
      unreachableStrategy = state.UnreachableDisabled,
      upgradeStrategy = state.UpgradeStrategy(0.0, 0.0),
      networks = Seq(HostNetwork),
      instances = 1,
      role = BaseMarathon.defaultRole
    )

    val body:Pod = Raml.toRaml(pod)
    val bodyString = Json.prettyPrint(Pod.playJsonFormat.writes(body))

    When("The pod is deployed")
    val createResult = marathon.createPodV2(pod)
    createResult should be(Created)
    waitForDeployment(createResult)

    val (ipcInfo1:String, ipcInfo2:String) = eventually {
      marathon.status(pod.id) should be(Stable)
      val status = marathon.status(pod.id).value

      val hosts = status.instances.flatMap(_.agentHostname)
      hosts should have size (1)
      val ports = status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))
      ports should have size (2)
      val facade1 = AppMockFacade(hosts.head, ports.head)
      val facade2 = AppMockFacade(hosts.head, ports.last)

      val ipcInfoString1 = facade1.get(s"/ipcinfo").futureValue
      val ipcInfoString2 = facade2.get(s"/ipcinfo").futureValue

      logger.info("IPCInfo1 is: " + ipcInfoString1)
      logger.info("IPCInfo2 is: " + ipcInfoString2)

      ipcInfoString1 should include("=======")
      ipcInfoString2 should include("=======")

      (ipcInfoString1, ipcInfoString2)
    }


    Then("The SharedMemory size for both containers should match the one defined on the pod level")
    val shmFsSizeRegex = "tmpfs\\s+([0-9]+)\\s+[0-9]+\\s+[0-9]+\\s+[0-9]+%\\s+/dev/shm".r

    val shmFsSizeMatch1 = shmFsSizeRegex.findFirstMatchIn(ipcInfo1)
    val shmFsSizeMatch2 = shmFsSizeRegex.findFirstMatchIn(ipcInfo2)

    val shmSize1 = shmFsSizeMatch1.value.group(1)
    val shmSize2 = shmFsSizeMatch2.value.group(1)

    shmSize1 should be("" + shmSize)
    shmSize2 should be("" + shmSize)

    Then("The IPC ID should be the same for both containers")
    val ipcIdRegex = "=======[0-9]+\\s+=======".r

    val ipcIdMatch1 = ipcIdRegex.findFirstMatchIn(ipcInfo1)
    val ipcIdMatch2 = ipcIdRegex.findFirstMatchIn(ipcInfo2)

    ipcIdMatch1 should be(ipcIdMatch2)


  }


}
