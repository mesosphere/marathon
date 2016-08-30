package mesosphere.marathon.core.instance

import org.apache.mesos

/**
  * To define the status of an Instance, this trait is used and stored for each Task in Task.Status.
  * The existing case objects are:
  * - marathon exclusive status
  * - representations of the mesos.Protos.TaskStatus
  * - mapping of existing (soon-to-be deprecated) mesos.Protos.TaskStatus.TASK_LOST to the new representations
  */
sealed trait InstanceStatus {
  def toMesosStateName: String = {
    import InstanceStatus._
    this match {
      case Gone | Unreachable | Unknown | Dropped => mesos.Protos.TaskState.TASK_LOST.toString
      case Created | Reserved => mesos.Protos.TaskState.TASK_STAGING.toString
      case s: InstanceStatus => "TASK_" + s.toString.toUpperCase()
    }
  }
}

object InstanceStatus {

  sealed trait Terminal

  // Reserved: Task with persistent volume has reservation, but is not launched yet
  case object Reserved extends InstanceStatus

  // Created: Task is known in marathon and sent to mesos, but not staged yet
  case object Created extends InstanceStatus

  // Error: indicates that a task launch attempt failed because of an error in the task specification
  case object Error extends InstanceStatus with Terminal

  // Failed: task aborted with an error
  case object Failed extends InstanceStatus with Terminal

  // Finished: task completes successfully
  case object Finished extends InstanceStatus with Terminal

  // Killed: task was killed
  case object Killed extends InstanceStatus with Terminal

  // Killing: the request to kill the task has been received, but the task has not yet been killed
  case object Killing extends InstanceStatus

  // Running: the state after the task has begun running successfully
  case object Running extends InstanceStatus

  // Staging: the master has received the framework’s request to launch the task but the task has not yet started to run
  case object Staging extends InstanceStatus

  // Starting: task is currently starting
  case object Starting extends InstanceStatus

  // Unreachable: the master has not heard from the agent running the task for a configurable period of time
  case object Unreachable extends InstanceStatus

  // Gone: the task was running on an agent that has been terminated
  case object Gone extends InstanceStatus with Terminal

  // Dropped: the task failed to launch because of a transient error (e.g., spontaneously disconnected agent)
  case object Dropped extends InstanceStatus with Terminal

  // Unknown: the master has no knowledge of the task
  case object Unknown extends InstanceStatus with Terminal

}
