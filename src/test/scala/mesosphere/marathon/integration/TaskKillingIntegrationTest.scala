package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.state.PathId._

@IntegrationTest
class TaskKillingIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {
  override val marathonArgs: Map[String, String] = Map("enable_features" -> "task_killing")

  "TaskKilling" should {
    "Killing a task publishes a TASK_KILLING event" in {
      Given("a new app")
      val app = appProxy(testBasePath / "app", "v1", instances = 1, healthCheck = None)

      When("The app is deployed")
      val createResult = marathon.createAppV2(app)

      Then("The app is created")
      createResult should be(Created)
      extractDeploymentIds(createResult) should have size 1
      waitForDeployment(createResult)
      waitForTasks(app.id.toPath, 1) //make sure, the app has really started

      When("the task is killed")
      val killResult = marathon.killAllTasksAndScale(app.id.toPath)
      killResult should be(OK)
      waitForStatusUpdates("TASK_KILLING")
    }
  }
}
