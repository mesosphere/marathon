package mesosphere.marathon
package integration

import java.io.File
import java.nio.file.Paths

import scala.io.Source
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.raml.{App, Container, DockerContainer, EngineType, LinuxInfo, Seccomp}
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

class SeccompIntegratonTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosConfig = MesosConfig(isolation = Some("linux/seccomp"))

  override lazy val agentSeccompConfigDir = Some(Paths.get(sys.props.getOrElse("user.dir", "."), "tests/integration/src/test/resources/mesos/seccomp").toString)
  override lazy val agentSeccompDefaultProfile = Some(new File(agentSeccompConfigDir.get, "default.json").getAbsolutePath)

  logger.info(s"--seccomp_config_dir = ${agentSeccompConfigDir.get}")
  logger.info(s"--seccomp_profile_name = ${agentSeccompDefaultProfile.get}")
  logger.info(s"Seccomp profile: ${Source.fromFile(agentSeccompDefaultProfile.get).getLines.mkString("\n")}")

  "An app definition WITHOUT seccomp profile and unconfined = true" in {
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
