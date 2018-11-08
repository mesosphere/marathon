package mesosphere.marathon
package core.task.bus

import java.util.UUID

import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.PrefixInstance
import mesosphere.marathon.core.task.Task
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{TaskState, TaskStatus, TimeInfo}

object MesosTaskStatusTestHelper {
  def mesosStatus(
    state: TaskState,
    maybeHealthy: Option[Boolean] = None,
    maybeReason: Option[Reason] = None,
    maybeMessage: Option[String] = None,
    timestamp: Timestamp = Timestamp.zero,
    taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)): TaskStatus = {

    val mesosStatus = TaskStatus.newBuilder
      .setTaskId(taskId.mesosTaskId)
      .setState(state)
      .setTimestamp(timestamp.seconds.toDouble)
    maybeHealthy.foreach(mesosStatus.setHealthy)
    maybeReason.foreach(mesosStatus.setReason)
    maybeMessage.foreach(mesosStatus.setMessage)
    mesosStatus.build()
  }

  def mesosStatus(condition: Condition, taskId: Task.Id, since: Timestamp): Option[TaskStatus] = {
    condition match {
      case Condition.Provisioned => None
      case Condition.Error => Some(error(taskId))
      case Condition.Failed => Some(failed(taskId))
      case Condition.Finished => Some(finished(taskId))
      case Condition.Killed => Some(killed(taskId))
      case Condition.Killing => Some(killing(taskId))
      case Condition.Running => Some(running(taskId))
      case Condition.Staging => Some(staging(taskId))
      case Condition.Starting => Some(starting(taskId))
      case Condition.Unreachable | Condition.UnreachableInactive => Some(unreachable(taskId, since))
      case Condition.Gone => Some(gone(taskId))
      case Condition.Unknown => Some(unknown(taskId))
      case Condition.Dropped => Some(dropped(taskId))
      case _ => None
    }
  }

  private def newInstanceId() = Instance.Id(PathId("/my/app"), PrefixInstance, UUID.randomUUID())

  def running(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_RUNNING, taskId = taskId)
  def runningHealthy(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_RUNNING, maybeHealthy = Some(true), taskId = taskId)
  def runningUnhealthy(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_RUNNING, maybeHealthy = Some(false), taskId = taskId)
  def starting(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_STARTING, taskId = taskId)
  def staging(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_STAGING, taskId = taskId)
  def dropped(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_DROPPED, taskId = taskId)
  def failed(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_FAILED, taskId = taskId)
  def finished(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_FINISHED, taskId = taskId)
  def gone(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_GONE, taskId = taskId)
  def error(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_ERROR, taskId = taskId)
  def lost(reason: Reason, taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None), since: Timestamp = Timestamp.zero) =
    mesosStatus(TaskState.TASK_LOST, maybeReason = Some(reason), timestamp = since, taskId = taskId)
  def killed(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_KILLED, taskId = taskId)
  def killing(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_KILLING, taskId = taskId)
  def unknown(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None)) = mesosStatus(state = TaskState.TASK_UNKNOWN, taskId = taskId)
  def unreachable(taskId: Task.Id = Task.EphemeralOrReservedTaskId(newInstanceId(), None), since: Timestamp = Timestamp.zero) = {
    val time = TimeInfo.newBuilder().setNanoseconds(since.nanos)
    mesosStatus(state = TaskState.TASK_UNREACHABLE, taskId = taskId).toBuilder
      .setUnreachableTime(time)
      .build()
  }
}
