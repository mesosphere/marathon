package mesosphere.marathon.core.task.bus

import org.apache.mesos.Protos.TaskStatus

sealed trait MarathonTaskStatus {
  def terminal: Boolean = false
  def mesosStatus: Option[TaskStatus]
}

// FIXME (merge): Merge MarathonTaskStatus and MesosTaskStatus
object MarathonTaskStatus {
  import org.apache.mesos.Protos.TaskState._

  def apply(mesosStatus: TaskStatus): MarathonTaskStatus = {
    val constructor: Option[TaskStatus] => MarathonTaskStatus = mesosStatus.getState match {
      case TASK_STAGING => Staging
      case TASK_STARTING => Starting
      case TASK_RUNNING => Running
      case TASK_KILLING => Killing
      case TASK_FINISHED => Finished
      case TASK_FAILED => Failed
      case TASK_KILLED => Killed
      case TASK_LOST => Lost
      case TASK_ERROR => Error
    }
    constructor(Some(mesosStatus))
  }

  case class Staging(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  case class Starting(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  case class Running(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  case class Killing(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus

  // Note that we need to distinguish between TemporarilyUnreachable tasks and really lost tasks that won't come back
  // tl;dr: don't use Lost
  case class Lost(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus

  sealed trait Terminal extends MarathonTaskStatus {
    override def terminal: Boolean = true
    def killed: Boolean = false
  }
  object Terminal {
    def unapply(terminal: Terminal): Option[Terminal] = Some(terminal)
    def unapply(status: MarathonTaskStatus): Option[MarathonTaskStatus] = status.mesosStatus match {
      case Some(MesosTaskStatus.Terminal(_)) => Some(status)
      case _ => None
    }
  }

  object WithMesosStatus {
    def unapply(marathonTaskStatus: MarathonTaskStatus): Option[TaskStatus] = marathonTaskStatus.mesosStatus
  }

  case class Finished(mesosStatus: Option[TaskStatus]) extends Terminal
  case class Failed(mesosStatus: Option[TaskStatus]) extends Terminal
  case class Killed(mesosStatus: Option[TaskStatus]) extends Terminal {
    override def killed: Boolean = true
  }
  case class Error(mesosStatus: Option[TaskStatus]) extends Terminal
}
