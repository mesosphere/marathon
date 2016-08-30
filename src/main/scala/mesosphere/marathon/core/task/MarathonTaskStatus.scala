package mesosphere.marathon.core.task

import mesosphere.marathon.core.instance.InstanceStatus
import mesosphere.marathon.core.task.state.MarathonTaskStatusMapping
import org.apache.mesos

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
      case TASK_LOST => taskStatus.getReason match {
        case reason: mesos.Protos.TaskStatus.Reason if MarathonTaskStatusMapping.Gone(reason) => Gone
        case reason: mesos.Protos.TaskStatus.Reason if MarathonTaskStatusMapping.Unreachable(reason) => Unreachable
        case reason: mesos.Protos.TaskStatus.Reason if MarathonTaskStatusMapping.Unknown(reason) => Unknown
        case _ => Dropped
      }
      case TASK_RUNNING => Running
      case TASK_STAGING => Staging
      case TASK_STARTING => Starting
    }
  }
}
