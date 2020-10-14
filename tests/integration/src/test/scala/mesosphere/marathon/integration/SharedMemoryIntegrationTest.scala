package mesosphere.marathon
package integration

import com.mesosphere.utils.mesos.MesosAgentConfig
import mesosphere.marathon.core.health.{MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.pod.{HostNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.integration.facades.AppMockFacade
import mesosphere.marathon.integration.setup.{BaseMarathon, EmbeddedMarathonTest}
import mesosphere.marathon.raml.{Pod, Raml}
import mesosphere.marathon.state.{HostVolume, VolumeMount}
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}
import org.scalatest.Inside

import scala.collection.immutable.Seq

class SharedMemoryIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {

  val projectDir: String = sys.props.getOrElse("user.dir", ".")
  override lazy val agentConfig = MesosAgentConfig(
    launcher = "linux",
    containerizers = "docker,mesos",
    isolation = Some("filesystem/linux,docker/runtime,namespaces/ipc"),
    imageProviders = Some("docker")
  )

  "get correct shm size from pod" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("a pod with a single task and a volume")
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
          linuxInfo = Some(
            state.LinuxInfo(
              seccomp = None,
              ipcInfo = Some(
                state.IPCInfo(
                  ipcMode = state.IpcMode.Private,
                  shmSize = Some(shmSize)
                )
              )
            )
          )
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
    val shmSizeFromPod: String = eventually {
      marathon.status(pod.id) should be(Stable)
      val status = marathon.status(pod.id).value
      val host = inside(status.instances.flatMap(_.agentHostname)) {
        case Seq(host) => host
      }
      val port = inside(status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))) {
        case Seq(port) => port
      }

      val facade = AppMockFacade(host, port)

      val shmSize = facade.get("/ipcshm").futureValue.entityString

      shmSize
    }

    Then("The shared memory size from the pod should be as configured")
    shmSizeFromPod should be(shmSize.toString)
  }

  "check share parent shm works" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("a pod with a two tasks and shareParent ipcInfo")
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
      linuxInfo = Some(
        state.LinuxInfo(
          seccomp = None,
          ipcInfo = Some(
            state.IPCInfo(
              ipcMode = state.IpcMode.Private,
              shmSize = Some(shmSize)
            )
          )
        )
      ),
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
          linuxInfo = Some(
            state.LinuxInfo(
              seccomp = None,
              ipcInfo = Some(
                state.IPCInfo(
                  ipcMode = state.IpcMode.ShareParent,
                  shmSize = None
                )
              )
            )
          )
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
          linuxInfo = Some(
            state.LinuxInfo(
              seccomp = None,
              ipcInfo = Some(
                state.IPCInfo(
                  ipcMode = state.IpcMode.ShareParent,
                  shmSize = None
                )
              )
            )
          )
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

    val body: Pod = Raml.toRaml(pod)

    When("The pod is deployed")
    val createResult = marathon.createPodV2(pod)
    createResult should be(Created)
    waitForDeployment(createResult)

    val (shmSize1, shmSize2, ipcNs1, ipcNs2) = eventually {
      marathon.status(pod.id) should be(Stable)
      val status = marathon.status(pod.id).value

      val host = inside(status.instances.flatMap(_.agentHostname)) {
        case Seq(h) => h
      }
      val (port1, port2) = inside(status.instances.flatMap(_.containers.flatMap(_.endpoints.flatMap(_.allocatedHostPort)))) {
        case Seq(p1, p2) => (p1, p2)
      }

      val facade1 = AppMockFacade(host, port1)
      val facade2 = AppMockFacade(host, port2)

      val shmSize1 = facade1.get(s"/ipcshm").futureValue.entityString
      val shmSize2 = facade2.get(s"/ipcshm").futureValue.entityString

      val ipcNs1 = facade1.get(s"/ipcns").futureValue.entityString
      val ipcNs2 = facade2.get(s"/ipcns").futureValue.entityString

      (shmSize1, shmSize2, ipcNs1, ipcNs2)
    }

    Then("The SharedMemory size for both containers should match the one defined on the pod level")

    shmSize1 should be(shmSize.toString)
    shmSize2 should be(shmSize.toString)

    Then("The IPC ID should be the same for both containers")

    ipcNs1 should be(ipcNs2)

  }

}
