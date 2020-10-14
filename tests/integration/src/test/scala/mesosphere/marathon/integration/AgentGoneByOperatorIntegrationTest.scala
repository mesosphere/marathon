package mesosphere.marathon
package integration

import com.mesosphere.utils.http.RestResult
import com.mesosphere.utils.mesos.MesosConfig
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.TaskIdWithIncarnation
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.App
import mesosphere.marathon.state.AbsolutePathId
import org.scalatest.Inside

class AgentGoneByOperatorIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {

  override lazy val mesosConfig = MesosConfig(
    numAgents = 2
  )

  "resident task will be expunged and a new instance will be created in response to TASK_GONE_BY_OPERATOR" in {
    Given("An app with a persistent volume")
    val containerPath = "persistent-volume"
    val id = appId("resident-task-with-persistent-volume-will-expunge-with-gone-by-operator")
    val app = residentApp(id = id, containerPath = containerPath, cmd = "sleep 36000")

    When("a task is launched")
    createSuccessfully(app)

    And("the matching agent is marked gone")
    val Seq(oldTask) = marathon.tasks(id).value

    mesosFacade.markAgentGone(oldTask.slaveId.get).success shouldBe true

    val oldTaskId = inside(Task.Id.parse(oldTask.id)) {
      case t: TaskIdWithIncarnation => t
    }

    Then("A replacement is launched on a different agent")
    eventually {
      val Seq(newTask) = marathon.tasks(id).value
      val newTaskId = inside(Task.Id.parse(newTask.id)) {
        case t: TaskIdWithIncarnation => t
      }

      oldTaskId shouldNot equal(newTaskId)
      newTask.slaveId.shouldNot(equal(oldTask.slaveId))
      oldTaskId.instanceId should equal(newTaskId.instanceId)
      newTaskId.incarnation should be > oldTaskId.incarnation
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

  def appId(suffix: String): AbsolutePathId = AbsolutePathId(s"/$testBasePath/app-$suffix")
}
