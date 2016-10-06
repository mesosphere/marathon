package mesosphere.marathon.core.task

import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.task.state.MarathonTaskStatusMapping
import org.apache.mesos
import org.apache.mesos.Protos.TaskStatus.Reason

object MarathonTaskStatus {

  import org.apache.mesos.Protos.TaskState._
  import InstanceStatus._

  //scalastyle:off cyclomatic.complexity
  def apply(taskStatus: mesos.Protos.TaskStatus): InstanceStatus = {
    taskStatus.getState match {
      case TASK_ERROR => Error
      case TASK_FAILED => Failed
      case TASK_FINISHED => Finished
      case TASK_KILLED => Killed
      case TASK_KILLING => Killing
      case TASK_LOST => inferStateForLost(taskStatus.getReason, taskStatus.getMessage)
      case TASK_RUNNING => Running
      case TASK_STAGING => Staging
      case TASK_STARTING => Starting

      case TASK_DROPPED => Dropped
      case TASK_GONE | TASK_GONE_BY_OPERATOR => Gone
      case TASK_UNKNOWN => Unknown
      case TASK_UNREACHABLE => Unreachable
      // FIXME (gkleiman): REMOVE ONCE MARATHON IS PARTITION_AWARE
      case _ => Error
    }
  }

  private[this] val MessageIndicatingUnknown = "Reconciliation: Task is unknown to the"

  private[this] def inferStateForLost(reason: Reason, message: String): InstanceStatus = {
    if (message.startsWith(MessageIndicatingUnknown) || MarathonTaskStatusMapping.Unknown(reason)) {
      Unknown
    } else if (MarathonTaskStatusMapping.Gone(reason)) {
      Gone
    } else if (MarathonTaskStatusMapping.Unreachable(reason)) {
      Unreachable
    } else {
      Dropped
    }
  }
}
