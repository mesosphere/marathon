package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade.extractDeploymentIds
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.{App, AppCheck, CommandCheck, ShellCommand}
import mesosphere.marathon.state.AbsolutePathId

class MesosCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  def appId(suffix: Option[String] = None): AbsolutePathId = testBasePath / s"app-${suffix.getOrElse(UUID.randomUUID)}"
  val appCommand: String = s"sleep 100"

  "AppDeploy with mesos checks" should {
    "provide a command check result status of 0" in {
      Given("a new app with checks")
      val id = appId(Some("with-successful-checks"))
      val app = App(id.toString, cmd = Some(appCommand), check = Some(ramlCommandCheck("true")))

      When("The app deploys")
      val result = marathon.createAppV2(app)

      Then("The app is created with a successful exit code")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      eventually {
        val currentTasks = marathon.tasks(id).value
        currentTasks should have size (1)
        currentTasks.head.check.get.command.get.exitCode should be(0)
      }
      marathon.deleteApp(id)
    }

    "provide a command check result status of 1" in {
      Given("a new app")
      val id = appId(Some("with-failing-checks"))
      val app = App(id.toString, cmd = Some(appCommand), check = Some(ramlCommandCheck("false")))

      When("the app is created")
      val result = marathon.createAppV2(app)

      Then("the app deploys successfully")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      And("the failing check status is exposed in /v2/tasks")
      eventually {
        val currentTasks = marathon.tasks(id).value
        currentTasks should have size (1)
        currentTasks.head.check.get.command.get.exitCode should be(1)
      }
      marathon.deleteApp(id)
    }
  }

  private def ramlCommandCheck(command: String) =
    AppCheck(
      exec = Some(CommandCheck(ShellCommand(command))),
      intervalSeconds = 1,
      delaySeconds = 1
    )
}
