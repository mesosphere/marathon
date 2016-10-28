package mesosphere.marathon.core.task.bus

import java.util.UUID
import java.util.concurrent.TimeUnit

import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.core.task.Task
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{ TaskState, TaskStatus, TimeInfo }

object MesosTaskStatusTestHelper {
  def mesosStatus(
    state: TaskState,
    maybeHealthy: Option[Boolean] = None,
    maybeReason: Option[Reason] = None,
    maybeMessage: Option[String] = None,
    timestamp: Timestamp = Timestamp.zero,
    taskId: Task.Id = Task.Id(UUID.randomUUID().toString)): TaskStatus = {

    val mesosStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(state)
      .setTimestamp(TimeUnit.MILLISECONDS.toMicros(timestamp.toDateTime.getMillis).toDouble)
    maybeHealthy.foreach(mesosStatus.setHealthy)
    maybeReason.foreach(mesosStatus.setReason)
    maybeMessage.foreach(mesosStatus.setMessage)
    mesosStatus.build()
  }

  def running(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_RUNNING, taskId = taskId)
  def runningHealthy(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_RUNNING, maybeHealthy = Some(true), taskId = taskId)
  def runningUnhealthy(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_RUNNING, maybeHealthy = Some(false), taskId = taskId)
  def starting(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_STARTING, taskId = taskId)
  def staging(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_STAGING, taskId = taskId)
  def dropped(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_DROPPED, taskId = taskId)
  def failed(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_FAILED, taskId = taskId)
  def finished(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_FINISHED, taskId = taskId)
  def gone(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_GONE, taskId = taskId)
  def error(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_ERROR, taskId = taskId)
  def lost(reason: Reason, taskId: Task.Id = Task.Id(UUID.randomUUID().toString), since: Timestamp = Timestamp.zero) =
    mesosStatus(TaskState.TASK_LOST, maybeReason = Some(reason), timestamp = since, taskId = taskId)
  def killed(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_KILLED, taskId = taskId)
  def killing(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_KILLING, taskId = taskId)
  def unknown(taskId: Task.Id = Task.Id(UUID.randomUUID().toString)) = mesosStatus(state = TaskState.TASK_UNKNOWN, taskId = taskId)
  def unreachable(taskId: Task.Id = Task.Id(UUID.randomUUID().toString), since: Timestamp = Timestamp.zero) = {
    val time = TimeInfo.newBuilder().setNanoseconds(since.toDateTime.getMillis * 1000000)
    mesosStatus(state = TaskState.TASK_UNREACHABLE, taskId = taskId).toBuilder
      .setUnreachableTime(time)
      .build()
  }
}
