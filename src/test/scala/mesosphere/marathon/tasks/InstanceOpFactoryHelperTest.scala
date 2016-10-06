package mesosphere.marathon.tasks

import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper, Mockito }
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.{ GivenWhenThen, Matchers }

class InstanceOpFactoryHelperTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {

  test("exception when newTask.taskId and taskInfo.id don't match") {
    val f = new Fixture

    Given("A non-matching task and taskInfo")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(f.runSpecId)
    val task: Task.LaunchedEphemeral = builder.pickFirstTask()
    val taskInfo = MarathonTestHelper.makeOneCPUTask(Task.Id.forRunSpec(f.runSpecId)).build()

    When("We create a launch operation")
    val error = intercept[AssertionError] {
      f.helper.launchEphemeral(taskInfo, task, builder.getInstance())
    }

    Then("An exception is thrown")
    error.getMessage shouldEqual "assumption failed: marathon task id and mesos task id must be equal"
  }

  test("Create a launch TaskOp") {
    val f = new Fixture

    Given("a task and a taskInfo")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(f.runSpecId)
    val instance = builder.getInstance()
    val task: Task.LaunchedEphemeral = builder.pickFirstTask()
    val taskInfo = MarathonTestHelper.makeOneCPUTask(task.taskId).build()

    When("We create a launch operation")
    val launch = f.helper.launchEphemeral(taskInfo, task, instance)

    Then("The result is as expected")
    launch.stateOp shouldEqual InstanceUpdateOperation.LaunchEphemeral(instance)
    launch.taskInfo shouldEqual taskInfo
    launch.oldInstance shouldBe empty
    launch.offerOperations should have size 1
    launch.offerOperations.head.getType shouldEqual Mesos.Offer.Operation.Type.LAUNCH
  }

  class Fixture {
    val runSpecId = PathId("/test")
    val helper = new InstanceOpFactoryHelper(Some("principal"), Some("role"))
  }
}
