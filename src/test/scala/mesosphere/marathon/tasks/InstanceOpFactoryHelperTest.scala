package mesosphere.marathon
package tasks

import com.fasterxml.uuid.Generators
import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{Protos => Mesos}

class InstanceOpFactoryHelperTest extends UnitTest {

  class Fixture {
    val runSpecId = PathId("/test")
    val metrics = DummyMetrics
    val helper = new InstanceOpFactoryHelper(metrics, Some("principal"), Some("role"))

    val uuidGenerator = Generators.timeBasedGenerator()
  }

  "InstanceOpFactoryHelper" should {
    "exception when newTask.taskId and taskInfo.id don't match" in {
      val f = new Fixture

      Given("A non-matching task and taskInfo")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(f.runSpecId).getInstance()
      val task: Task = instance.appTask
      val otherTaskId = Task.LegacyId(f.runSpecId, "-", f.uuidGenerator.generate())
      val taskInfo = MarathonTestHelper.makeOneCPUTask(otherTaskId).build()

      When("We create a launch operation")
      val error = intercept[AssertionError] {
        f.helper.provision(taskInfo, instance.instanceId, instance.agentInfo.get, instance.runSpec, task, Timestamp.now())
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
      val now = Timestamp.now()
      val launch = f.helper.provision(taskInfo, instance.instanceId, instance.agentInfo.get, instance.runSpec, task, now)

      Then("The result is as expected")
      launch.stateOp shouldEqual InstanceUpdateOperation.Provision(instance.instanceId, instance.agentInfo.get, instance.runSpec, Seq(task), now)
      launch.taskInfo shouldEqual taskInfo
      launch.oldInstance shouldBe empty
      launch.offerOperations should have size 1
      launch.offerOperations.head.getType shouldEqual Mesos.Offer.Operation.Type.LAUNCH
    }

  }
}
