package mesosphere.marathon.core.task.state

import org.apache.mesos

/**
  * To define the status of a Task, this trait is used and stored for each Task in Task.Status.
  * The existing case objects are:
  * - marathon exclusive status
  * - representations of the mesos.Protos.TaskStatus
  * - mapping of existing (soon-to-be deprecated) mesos.Protos.TaskStatus.TASK_LOST to the new representations
  */
sealed trait MarathonTaskStatus

object MarathonTaskStatus {
  import org.apache.mesos.Protos.TaskState._

  sealed trait Terminal

  //scalastyle:off cyclomatic.complexity
  def apply(taskStatus: mesos.Protos.TaskStatus): MarathonTaskStatus = {
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

  // Reserved: Task with persistent volume has reservation, but is not launched yet
  case object Reserved extends MarathonTaskStatus

  // Created: Task is known in marathon and sent to mesos, but not staged yet
  case object Created extends MarathonTaskStatus

  // Error: indicates that a task launch attempt failed because of an error in the task specification
  case object Error extends MarathonTaskStatus with Terminal

  // Failed: task aborted with an error
  case object Failed extends MarathonTaskStatus with Terminal

  // Finished: task completes successfully
  case object Finished extends MarathonTaskStatus with Terminal

  // Killed: task was killed
  case object Killed extends MarathonTaskStatus with Terminal

  // Killing: the request to kill the task has been received, but the task has not yet been killed
  case object Killing extends MarathonTaskStatus

  // Running: the state after the task has begun running successfully
  case object Running extends MarathonTaskStatus

  // Staging: the master has received the framework’s request to launch the task but the task has not yet started to run
  case object Staging extends MarathonTaskStatus

  // Starting: task is currently starting
  case object Starting extends MarathonTaskStatus

  // Unreachable: the master has not heard from the agent running the task for a configurable period of time
  case object Unreachable extends MarathonTaskStatus

  // Gone: the task was running on an agent that has been terminated
  case object Gone extends MarathonTaskStatus with Terminal

  // Dropped: the task failed to launch because of a transient error (e.g., spontaneously disconnected agent)
  case object Dropped extends MarathonTaskStatus with Terminal

  // Unknown: the master has no knowledge of the task
  case object Unknown extends MarathonTaskStatus with Terminal

}
