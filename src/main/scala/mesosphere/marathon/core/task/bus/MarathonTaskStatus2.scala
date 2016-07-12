package mesosphere.marathon.core.task.bus

import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.Protos.TaskStatus.Reason._

sealed trait MarathonTaskStatus2 {
  // val garbage: Boolean TODO
  protected def mesosStatus: Option[TaskStatus]
}

object MarathonTaskStatus2 {
  import org.apache.mesos.Protos.TaskState._

  // If we're disconnected at the time of a TASK_LOST event, we will only get the update during
  // a reconciliation. In that case, the specific reason will be shadowed by REASON_RECONCILIATION.
  // Since we don't know the original reason, we need to assume that the task might come back.
  val MightComeBack: Set[TaskStatus.Reason] = Set(
    REASON_RECONCILIATION,
    REASON_SLAVE_DISCONNECTED,
    REASON_SLAVE_REMOVED
  )

  val WontComeBack: Set[TaskStatus.Reason] = TaskStatus.Reason.values().toSet.diff(MightComeBack)

  trait NoMesosStatus {
    protected def mesosStatus: Option[TaskStatus] = None
  }

  trait IsTerminal

  def apply(taskStatus: TaskStatus): MarathonTaskStatus2 = {

    def transformLostMesosStatus(taskStatus: Option[TaskStatus]) = {
      if (WontComeBack(taskStatus.get.getReason)) {
        Gone(taskStatus)
      } else if (MightComeBack(taskStatus.get.getReason)) {
        Unreachable(taskStatus)
      } else {
        Lost(taskStatus)
      }
    }
    val option = Some(taskStatus)

    taskStatus.getState match {
      case TASK_ERROR => Error(option)
      case TASK_FAILED => Failed(option)
      case TASK_FINISHED => Finished(option)
      case TASK_KILLED => Killed(option)
      case TASK_KILLING => Killing(option)
      case TASK_LOST => transformLostMesosStatus(option)
      case TASK_RUNNING => Running(option)
      case TASK_STAGING => Staging(option)
      case TASK_STARTING => Starting(option)
    }
  }

  // Marathon specific states
  // RESERVED
  case class Reserved() extends MarathonTaskStatus2 with NoMesosStatus
  // CREATED
  case class Created() extends MarathonTaskStatus2 with NoMesosStatus

  // 'Native' mesos TaskStates
  // ERROR
  case class Error(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal
  // FAILED
  case class Failed(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal
  // FINISHED
  case class Finished(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal
  // KILLED
  case class Killed(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal
  // KILLING
  case class Killing(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2
  // LOST
  case class Lost(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal
  // RUNNING
  case class Running(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2
  // STAGING
  case class Staging(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2
  // STARTING
  case class Starting(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2

  // Temporarily transformation states, should become mesos native in the future
  // UNREACHABLE
  case class Unreachable(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2
  // GONE
  case class Gone(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal
  // LOST
  // already defined as 'native' mesos state
  // UNKNOWN
  case class Unknown(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus2 with IsTerminal

}
