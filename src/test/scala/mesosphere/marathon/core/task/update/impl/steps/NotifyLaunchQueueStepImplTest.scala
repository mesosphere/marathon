package mesosphere.marathon.core.task.update.impl.steps

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.state.{ Timestamp, PathId }
import mesosphere.marathon.tasks.TaskIdUtil
import mesosphere.marathon.test.Mockito
import org.apache.mesos.Protos.{ TaskState, TaskStatus, SlaveID }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers, FunSuite }

import scala.concurrent.Future

class NotifyLaunchQueueStepImplTest extends FunSuite with Matchers with GivenWhenThen with Mockito with ScalaFutures {
  test("name") {
    new Fixture().step.name should equal("notifyLaunchQueue")
  }

  test("notifying launch queue") {
    val f = new Fixture
    val status = runningTaskStatus
    val expectedUpdate = TaskStatusUpdate(updateTimestamp, taskId, MarathonTaskStatus(status))

    Given("a status update")
    f.launchQueue.notifyOfTaskUpdate(expectedUpdate) returns Future.successful(None)

    When("calling processUpdate")
    f.step.processUpdate(
      updateTimestamp,
      appId,
      task = MarathonTestHelper.dummyTask(appId),
      status = status
    ).futureValue

    Then("the update is passed to the LaunchQueue")
    verify(f.launchQueue).notifyOfTaskUpdate(expectedUpdate)
  }

  private[this] val slaveId = SlaveID.newBuilder().setValue("slave1")
  private[this] val appId = PathId("/test")
  private[this] val taskId = TaskIdUtil.newTaskId(appId)
  private[this] val updateTimestamp = Timestamp(100)
  private[this] val taskStatusMessage = "some update"

  private[this] val runningTaskStatus =
    TaskStatus
      .newBuilder()
      .setState(TaskState.TASK_RUNNING)
      .setTaskId(taskId)
      .setSlaveId(slaveId)
      .setMessage(taskStatusMessage)
      .build()

  class Fixture {
    val launchQueue = mock[LaunchQueue]
    val step = new NotifyLaunchQueueStepImpl(launchQueue = launchQueue)
  }
}
