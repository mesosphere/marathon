package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, RestResult}
import mesosphere.marathon.raml.App
import mesosphere.marathon.state.PathId
import org.scalatest.Inside

class AgentGoneByOperatorIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {
  override lazy val mesosNumSlaves = 2

  "resident task will be expunged and a new instance will be created in response to TASK_GONE_BY_OPERATOR" in {
    Given("An app with a persistent volume")
    val containerPath = "persistent-volume"
    val id = appId("resident-task-with-persistent-volume-will-expunge-with-gone-by-operator")
    val app = residentApp(
      id = id,
      containerPath = containerPath,
      cmd = "sleep 36000")

    When("a task is launched")
    createSuccessfully(app)

    And("the matching agent is marked gone")
    val task = inside(marathon.tasks(id).value) {
      case Seq(task) => task
    }

    mesos.markAgentGone(task.slaveId.get).success shouldBe true

    Then("A replacement is launched on a different agent")
    eventually {
      val Seq(newTask) = marathon.tasks(id).value

      newTask.id.shouldNot(equal(task.id))
      newTask.slaveId.shouldNot(equal(task.slaveId))
    }
  }

  def createSuccessfully(app: App): App = {
    waitForDeployment(createAsynchronously(app))
    app
  }

  def createAsynchronously(app: App): RestResult[App] = {
    val result = marathon.createAppV2(app)
    result should be(Created)
    extractDeploymentIds(result) should have size 1
    result
  }

  def appId(suffix: String): PathId = PathId(s"/$testBasePath/app-$suffix")
}
