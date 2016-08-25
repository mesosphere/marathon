package mesosphere.marathon.core.task.bus

import java.util.UUID

import mesosphere.mesos.protos.TaskID
import org.apache.mesos.Protos.TaskStatus.Reason
import org.apache.mesos.Protos.{ TaskState, TaskStatus }

object MesosTaskStatusTestHelper {
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

  val running = mesosStatus(TaskState.TASK_RUNNING)
  val runningHealthy = mesosStatus(TaskState.TASK_RUNNING, maybeHealthy = Some(true))
  val runningUnhealthy = mesosStatus(TaskState.TASK_RUNNING, maybeHealthy = Some(false))
  val starting = mesosStatus(TaskState.TASK_STARTING)
  val staging = mesosStatus(TaskState.TASK_STAGING)
  val finished = mesosStatus(TaskState.TASK_FINISHED)
  val error = mesosStatus(TaskState.TASK_ERROR)
  def lost(reason: Reason) = mesosStatus(TaskState.TASK_LOST, maybeReason = Some(reason))
  val killed = mesosStatus(TaskState.TASK_KILLED)
}
