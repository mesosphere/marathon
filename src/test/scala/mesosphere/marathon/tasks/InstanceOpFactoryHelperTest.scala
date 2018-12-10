package mesosphere.marathon
package tasks

import com.fasterxml.uuid.Generators
import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
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

    "Create a launch TaskOp" in {
      val f = new Fixture

      Given("a task and a taskInfo")
      val instance = TestInstanceBuilder.newBuilderWithLaunchedTask(f.runSpecId).getInstance()
      val task: Task = instance.tasksMap.values.head
      val taskInfo = MarathonTestHelper.makeOneCPUTask(task.taskId).build()

      When("We create a launch operation")
      val now = Timestamp.now()
      val stateOp = InstanceUpdateOperation.Provision(instance.instanceId, instance.agentInfo.get, instance.runSpec, instance.tasksMap, now)
      val launch = f.helper.provision(taskInfo, stateOp)

      Then("The result is as expected")
      launch.stateOp shouldEqual stateOp
      launch.taskInfo shouldEqual taskInfo
      launch.oldInstance shouldBe empty
      launch.offerOperations should have size 1
      launch.offerOperations.head.getType shouldEqual Mesos.Offer.Operation.Type.LAUNCH
    }

  }
}
