package mesosphere.marathon
package core.task

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{ Instance, TestTaskBuilder }
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.SettableClock
import org.apache.mesos.Protos.TaskState

import scala.concurrent.duration._

class TaskStatusUpdateTest extends UnitTest {

  def unreachableEffect(effect: => TaskUpdateEffect): Unit = {

    "return an effect that" should {

      "result in an update" in {
        effect shouldBe a[TaskUpdateEffect.Update]
      }
      "update to unreachable task status" in {
        val newStatus = effect.asInstanceOf[TaskUpdateEffect.Update].newState.status.mesosStatus.get.getState
        newStatus should be(TaskState.TASK_UNREACHABLE)
      }
      "update to unreachable instance status" in {
        val newStatus = effect.asInstanceOf[TaskUpdateEffect.Update].newState.status.condition
        newStatus should be(Condition.Unreachable)
      }

    }
  }

  "LaunchedEphemeral" when {
    "updating a running task with a TASK_UNREACHABLE" should {
      val f = new Fixture

      val task = TestTaskBuilder.Helper.minimalRunning(appId = f.appId, since = f.clock.now())
      val instance = mock[Instance]
      instance.hasReservation returns false

      f.clock += 5.seconds

      val status = MesosTaskStatusTestHelper.unreachable(task.taskId, f.clock.now())

      val effect = task.update(instance, TaskCondition(status), status, f.clock.now())

      behave like unreachableEffect(effect)
    }
  }

  "LaunchedOnReservation" when {
    "updating a running task with a TASK_UNREACHABLE" should {
      val f = new Fixture
      val task = TestTaskBuilder.Helper.residentLaunchedTask(f.appId)
      val instance = mock[Instance]
      instance.hasReservation returns true

      f.clock += 5.seconds

      val status = MesosTaskStatusTestHelper.unreachable(task.taskId, f.clock.now())

      val effect = task.update(instance, TaskCondition(status), status, f.clock.now())

      behave like unreachableEffect(effect)
    }
  }

  class Fixture {
    val appId = PathId("/app")
    val clock = new SettableClock()
  }
}
