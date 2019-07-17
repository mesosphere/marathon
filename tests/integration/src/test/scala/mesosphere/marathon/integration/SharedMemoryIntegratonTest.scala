package mesosphere.marathon
package integration

import mesosphere.marathon.core.health.{MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.pod.{HostNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.integration.facades.AppMockFacade
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.state.{HostVolume, VolumeMount}
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}

import scala.collection.immutable.Seq

class SharedMemoryIntegratonTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  val projectDir: String = sys.props.getOrElse("user.dir", ".")
  override lazy val mesosConfig = MesosConfig(
    launcher = "linux",
    containerizers = "docker,mesos",
    isolation = Some("filesystem/linux,docker/runtime,namespaces/ipc"),
    imageProviders = Some("docker")
  )

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
      role = "foo"
    )

    When("The pod is deployed")
    val createResult = marathon.createPodV2(pod)
    createResult should be(Created)
    waitForDeployment(createResult)
    eventually {
      marathon.status(pod.id) should be(Stable)
      val status = marathon.status(pod.id).value
      val hosts = status.instances.flatMap(_.agentHostname)
      hosts should have size (1)
      val ports = status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))
      ports should have size (1)
      val facade = AppMockFacade(hosts.head, ports.head)

      val ipcInfoString = facade.get(s"/ipcinfo").futureValue

      logger.info("IPCInfo is: " + ipcInfoString)

      val shmFsSizeRegex = "tmpfs\\s+([0-9]+)\\s+[0-9]+\\s+[0-9]+%\\s+/dev/shm".r
      val shmFsSizeMatch = shmFsSizeRegex.findFirstMatchIn(ipcInfoString)

      shmFsSizeMatch.value.group(1) should be(shmSize)

      facade
    }

  }

}
