package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.state.PathId._

class TaskKillingIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {
  override val marathonArgs: Map[String, String] = Map("enable_features" -> "task_killing")

  "TaskKilling" should {
    "Killing a task publishes a TASK_KILLING event" in {
      Given("a new app")
      val app = appProxy(testBasePath / "app-to-kill", "v1", instances = 1, healthCheck = None)

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

      // We used to wait for TASK_KILLING here, however in rare cases the task would start and then fail immediately e.g.
      // because the port for `app_mock` is already bound.
      // When marathon tries to kill a FAILED task it will receive a TASK_UNKNOWN status from mesos. The deployment will
      // succeed in the end since the task is gone but we'll never see TASK_KILLING event.
      // We can simply wait for the deployment to succeed but that defeats the purpose of this test (we test kill-and-scale
      // elsewhere.
      val waitingFor = Map[String, CallbackEvent => Boolean](
        "status_update_event" -> (_.taskStatus == "TASK_KILLING"),
        "unknown_instance_terminated_event" -> (_.info("instanceId").toString == app.id))
      waitForAnyEventWith(s"waiting for task ${app.id} to be removed", waitingFor)
    }

    "Killing an ephemeral task without wipe or scale causes a new task to be restarted with a higher incarnation count" in {
      Given("a new app")
      val app = appProxy(testBasePath / "ephemeral-app-to-kill", "v1", instances = 1, healthCheck = None)
      val appId = app.id.toPath

      When("The app is deployed")
      val createResult = marathon.createAppV2(app)

      Then("The app is created")
      createResult should be(Created)
      extractDeploymentIds(createResult) should have size 1
      waitForDeployment(createResult)
      waitForTasks(appId, 1) //make sure, the app has really started

      When("the task is killed")
      val Some(task) = marathon.tasks(appId).value.headOption

      val killResult = marathon.killTask(appId, task.id, false)
      killResult should be(OK)

      val taskId = Task.Id.parse(task.id)
      val nextTaskId = Task.Id.nextIncarnationFor(taskId)

      eventually {
        val Some(newTask) = marathon.tasks(appId).value.headOption
        newTask.id shouldBe nextTaskId.idString
      }
    }

    "Killing an ephemeral task with wipe causes a brand new instance to be created" in {
      Given("a new app")
      val app = appProxy(testBasePath / "ephemeral-app-to-wipe", "v1", instances = 1, healthCheck = None)
      val appId = app.id.toPath

      When("The app is deployed")
      val createResult = marathon.createAppV2(app)

      Then("The app is created")
      createResult should be(Created)
      extractDeploymentIds(createResult) should have size 1
      waitForDeployment(createResult)
      waitForTasks(appId, 1) //make sure, the app has really started

      When("the task is killed with wipe = true")
      val Some(task) = marathon.tasks(appId).value.headOption

      val killResult = marathon.killTask(appId, task.id, wipe = true)
      killResult should be(OK)

      val taskId = Task.Id.parse(task.id)
      val instanceId = taskId.instanceId

      eventually {
        val Some(newTask) = marathon.tasks(appId).value.headOption
        Task.Id.parse(newTask.id).instanceId shouldNot be(instanceId)
      }
    }
  }
}
