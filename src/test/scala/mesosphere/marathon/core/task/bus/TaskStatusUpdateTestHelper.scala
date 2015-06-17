package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.TaskIdUtil
import org.apache.mesos.Protos.TaskID
import org.joda.time.DateTime

class TaskStatusUpdateTestHelper(val wrapped: TaskStatusUpdate) {
  def withTaskId(taskId: String): TaskStatusUpdateTestHelper = {
    withTaskId(TaskID.newBuilder().setValue(taskId).build())
  }

  def withTaskId(taskId: TaskID): TaskStatusUpdateTestHelper = TaskStatusUpdateTestHelper {
    wrapped.copy(taskId = taskId)
  }

  def withAppId(appId: String): TaskStatusUpdateTestHelper = {
    withTaskId(TaskStatusUpdateTestHelper.newTaskID(appId))
  }

  def withStatus(status: MarathonTaskStatus): TaskStatusUpdateTestHelper = TaskStatusUpdateTestHelper {
    wrapped.copy(status = status)
  }
}

object TaskStatusUpdateTestHelper {
  def apply(update: TaskStatusUpdate): TaskStatusUpdateTestHelper =
    new TaskStatusUpdateTestHelper(update)

  private def newTaskID(appId: String) = {
    TaskIdUtil.newTaskId(PathId(appId))
  }

  val taskId = newTaskID("/app")

  val running = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.running
    )
  )

  val runningHealthy = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.runningHealthy
    )
  )

  val runningUnhealthy = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 30, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.runningUnhealthy
    )
  )

  val staging = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 31, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.staging
    )
  )

  val finished = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 31, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.finished
    )
  )

  val lost = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 31, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.lost
    )
  )

  val killed = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 31, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.killed
    )
  )

  val error = TaskStatusUpdateTestHelper(
    TaskStatusUpdate(
      timestamp = Timestamp.apply(new DateTime(2015, 2, 3, 12, 31, 0, 0)),
      taskId = taskId,
      status = MarathonTaskStatusTestHelper.error
    )
  )
}
