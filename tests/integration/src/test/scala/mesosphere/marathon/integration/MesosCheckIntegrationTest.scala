package mesosphere.marathon.integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade.extractDeploymentIds
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.{App, AppCheck, CommandCheck, ShellCommand}
import mesosphere.marathon.state.PathId

class MesosCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  def appId(suffix: Option[String] = None): PathId = testBasePath / s"app-${suffix.getOrElse(UUID.randomUUID)}"
  val appCommand: String = s"sleep 100"

  "AppDeploy with mesos checks" should {
    "provide a command check result status of 0" in {
      Given("a new app with checks")
      val id = appId(Some("with-successful-checks"))
      val app = App(
        id.toString,
        cmd = Some(appCommand),
        check = Some(ramlCommandCheck("true")))

      When("The app deploys")
      val result = marathon.createAppV2(app)

      Then("The app is created with a successful exit code")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      eventually {
        val currentTasks = marathon.tasks(id).value
        currentTasks should have size (1)
        currentTasks.head.check.get.command.get.exitCode should be (0)
      }
      marathon.deleteApp(id)
    }

    "provide a command check result status of 1" in {
      Given("a new app")
      val id = appId(Some("with-failing-checks"))
      val app = App(
        id.toString,
        cmd = Some(appCommand),
        check = Some(ramlCommandCheck("false")))

      When("The app deploys")
      val result = marathon.createAppV2(app)

      Then("The app is created with a failing exit code")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      eventually {
        val currentTasks = marathon.tasks(id).value
        currentTasks should have size (1)
        currentTasks.head.check.get.command.get.exitCode should be (1)
      }
      marathon.deleteApp(id)
    }
  }

  private def ramlCommandCheck(command: String) = AppCheck(
    exec = Some(CommandCheck(ShellCommand(command))),
    intervalSeconds = 1,
    delaySeconds = 1
  )
}
