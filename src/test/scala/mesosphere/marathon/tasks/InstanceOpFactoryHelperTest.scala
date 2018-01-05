package mesosphere.marathon
package tasks

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{ Protos => Mesos }

class InstanceOpFactoryHelperTest extends UnitTest {

  class Fixture {
    val runSpecId = PathId("/test")
    val helper = new InstanceOpFactoryHelper(Some("principal"), Some("role"))
  }

  "InstanceOpFactoryHelper" should {
    "exception when newTask.taskId and taskInfo.id don't match" in {
      val f = new Fixture

      Given("A non-matching task and taskInfo")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(f.runSpecId).getInstance()
      val task: Task = instance.appTask
      val taskInfo = MarathonTestHelper.makeOneCPUTask(Task.Id.forRunSpec(f.runSpecId)).build()

      When("We create a launch operation")
      val error = intercept[AssertionError] {
        f.helper.launchEphemeral(taskInfo, task, instance)
      }

      Then("An exception is thrown")
      error.getMessage shouldEqual "assumption failed: marathon task id and mesos task id must be equal"
    }

    "Create a launch TaskOp" in {
      val f = new Fixture

      Given("a task and a taskInfo")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(f.runSpecId).getInstance()
      val task: Task = instance.appTask
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

  }
}
