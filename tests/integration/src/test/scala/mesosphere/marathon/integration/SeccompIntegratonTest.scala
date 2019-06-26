package mesosphere.marathon
package integration

import mesosphere.{AkkaIntegrationTest, WhenEnvSet}
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType, LinuxInfo, Seccomp}
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

import scala.io.Source

class SeccompIntegratonTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosConfig = MesosConfig(isolation = Some("linux/seccomp"))

  val projectDir = sys.props.getOrElse("user.dir", ".")
  override lazy val agentSeccompConfigDir = Some(s"$projectDir/src/test/resources/mesos/seccomp")
  override lazy val agentSeccompProfileName = Some("default.json")

  logger.info(s"--seccomp_config_dir = ${agentSeccompConfigDir.get}")
  logger.info(s"--seccomp_profile_name = ${agentSeccompProfileName.get}")
  logger.debug(s"Seccomp profile: ${Source.fromFile(s"$projectDir/src/test/resources/mesos/seccomp/default.json").getLines.mkString("\n")}")

  "An app definition WITH seccomp profile defined and unconfined = false" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("an app WITH seccomp profile defined and unconfined = false")
    val app = seccompApp(PathId("app-with-seccomp-profile-and-unconfined-false"), unconfined = false, profileName = agentSeccompProfileName)

    When("the app is successfully deployed")
    val result = marathon.createAppV2(app)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    waitForTasks(app.id.toPath, 1)
  }

  "An app definition WITHOUT seccomp profile and unconfined = true" taggedAs WhenEnvSet(envVarRunMesosTests, default = "true") in {
    Given("an app WITHOUT seccomp profile and unconfined = true")
    val app = seccompApp(PathId("app-without-seccomp-profile-and-unconfined-true"), unconfined = true)

    When("the app is successfully deployed")
    val result = marathon.createAppV2(app)
    result should be(Created)
    waitForDeployment(result)

    And("the task is running")
    waitForTasks(app.id.toPath, 1)
  }

  def seccompApp(appId: PathId, unconfined: Boolean, profileName: Option[String] = None): App = {
    App(
      id = appId.toString,
      cmd = Some("sleep 232323"),
      instances = 1,
      cpus = 0.01,
      mem = 32.0,
      container = Some(
        Container(
          `type` = EngineType.Mesos,
          docker = Option(DockerContainer(image = "busybox")),
          linuxInfo = Option(LinuxInfo(
            seccomp = Option(Seccomp(
              unconfined = unconfined,
              profileName = profileName
            ))
          ))
        )
      )
    )
  }
}
