package mesosphere.marathon
package integration

import com.mesosphere.utils.mesos.MesosAgentConfig
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType, LinuxInfo, Seccomp}
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.{AkkaIntegrationTest, WhenEnvSet}

class SeccompIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  val projectDir: String = sys.props.getOrElse("user.dir", ".")
  override lazy val agentConfig = MesosAgentConfig(
    launcher = "linux",
    containerizers = "docker,mesos",
    isolation = Some("filesystem/linux,docker/runtime,linux/seccomp"),
    imageProviders = Some("docker"),
    seccompConfigDir = Some(s"$projectDir/src/test/resources/mesos/seccomp"),
    seccompProfileName = Some("default.json")
  )

  logger.info(s"Using --seccomp_config_dir = ${agentConfig.seccompConfigDir.get}")
  logger.info(s"Using --seccomp_profile_name = ${agentConfig.seccompProfileName.get}")

  "An app definition WITH seccomp profile defined and unconfined = false" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("an app WITH seccomp profile defined and unconfined = false")
    val app = seccompApp(
      AbsolutePathId("/app-with-seccomp-profile-and-unconfined-false"),
      unconfined = false,
      profileName = agentConfig.seccompProfileName
    )

    When("the app is successfully deployed")
    val result = marathon.createAppV2(app)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    waitForTasks(AbsolutePathId(app.id), app.instances)
  }

  "An app definition WITHOUT seccomp profile and unconfined = true" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("an app WITHOUT seccomp profile and unconfined = true")
    val app = seccompApp(AbsolutePathId("/app-without-seccomp-profile-and-unconfined-true"), unconfined = true)

    When("the app is successfully deployed")
    val result = marathon.createAppV2(app)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    waitForTasks(AbsolutePathId(app.id), app.instances)
  }

  "A pod definition WITH seccomp profile defined and unconfined = false" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("a pod WITH seccomp profile defined and unconfined = false")
    val pod = seccompPod(
      AbsolutePathId("/pod-with-seccomp-profile-and-unconfined-false"),
      unconfined = false,
      profileName = agentConfig.seccompProfileName
    )

    When("the pod is successfully deployed")
    val result = marathon.createPodV2(pod)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    eventually { marathon.status(pod.id) should be(Stable) }
  }

  "An pod definition WITHOUT seccomp profile and unconfined = true" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("a pod WITHOUT seccomp profile and unconfined = true")
    val pod = seccompPod(AbsolutePathId("/pod-without-seccomp-profile-and-unconfined-true"), unconfined = true)

    When("the pod is successfully deployed")
    val result = marathon.createPodV2(pod)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    eventually { marathon.status(pod.id) should be(Stable) }
  }

  def seccompPod(podId: AbsolutePathId, unconfined: Boolean, profileName: Option[String] = None): PodDefinition = {
    PodDefinition(
      id = podId,
      role = "foo",
      instances = 1,
      containers = Seq(
        MesosContainer(
          name = "task1",
          exec = Some(raml.MesosExec(raml.ShellCommand("sleep 9000"))),
          resources = raml.Resources(cpus = 0.01, mem = 32.0),
          linuxInfo = Some(
            state.LinuxInfo(
              seccomp = Some(
                state.Seccomp(
                  profileName,
                  unconfined
                )
              ),
              ipcInfo = None
            )
          )
        )
      )
    )
  }

  def seccompApp(appId: AbsolutePathId, unconfined: Boolean, profileName: Option[String] = None): App = {
    App(
      id = appId.toString,
      cmd = Some("sleep 232323"),
      cpus = 0.01,
      mem = 32.0,
      container = Some(
        Container(
          `type` = EngineType.Mesos,
          docker = Some(DockerContainer(image = "busybox")),
          linuxInfo = Some(
            LinuxInfo(
              seccomp = Some(
                Seccomp(
                  unconfined = unconfined,
                  profileName = profileName
                )
              )
            )
          )
        )
      )
    )
  }
}
