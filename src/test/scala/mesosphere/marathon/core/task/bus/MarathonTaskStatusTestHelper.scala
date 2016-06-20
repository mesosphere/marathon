package mesosphere.marathon.core.task.bus

import java.util.UUID

import mesosphere.mesos.protos.TaskID
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{ TaskState, TaskStatus }

object MarathonTaskStatusTestHelper {
  def mesosStatus(
    state: TaskState,
    maybeHealthy: Option[Boolean] = None,
    maybeReason: Option[Reason] = None): TaskStatus = {
    import mesosphere.mesos.protos.Implicits._

    val builder = TaskStatus.newBuilder()
    builder.setTaskId(TaskID(UUID.randomUUID().toString)).setState(state)
    maybeHealthy.foreach(builder.setHealthy)
    maybeReason.foreach(builder.setReason)
    builder.build()
  }

  val running = MarathonTaskStatus.Running(mesosStatus = Some(mesosStatus(TaskState.TASK_RUNNING)))
  val runningHealthy = MarathonTaskStatus.Running(
    mesosStatus = Some(mesosStatus(TaskState.TASK_RUNNING, maybeHealthy = Some(true))))
  val runningUnhealthy = MarathonTaskStatus.Running(
    mesosStatus = Some(mesosStatus(TaskState.TASK_RUNNING, maybeHealthy = Some(false))))
  val starting = MarathonTaskStatus.Starting(mesosStatus = Some(mesosStatus(TaskState.TASK_STARTING)))
  val staging = MarathonTaskStatus.Staging(mesosStatus = Some(mesosStatus(TaskState.TASK_STAGING)))
  val finished = MarathonTaskStatus.Finished(mesosStatus = Some(mesosStatus(TaskState.TASK_FINISHED)))
  val error = MarathonTaskStatus.Error(mesosStatus = Some(mesosStatus(TaskState.TASK_ERROR)))
  def lost(reason: Reason) = MarathonTaskStatus(mesosStatus = mesosStatus(TaskState.TASK_LOST, maybeReason = Some(reason)))
  val killed = MarathonTaskStatus.Killed(mesosStatus = Some(mesosStatus(TaskState.TASK_KILLED)))
}
