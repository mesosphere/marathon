package mesosphere.marathon.core.task.bus

// TODO ju organize imports
import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.Protos.TaskStatus.Reason._

sealed trait MarathonTaskStatus {
  // val garbage: Boolean TODO ju

  // TODO ju remove or make protected
  def mesosStatus: Option[TaskStatus]
}

object MarathonTaskStatus {
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
    def mesosStatus: Option[TaskStatus] = None
  }

  trait Terminal {
    def mesosStatus: Option[TaskStatus]

    def killed: Boolean = false
  }

  object WithMesosStatus {
    def unapply(marathonTaskStatus: MarathonTaskStatus): Option[TaskStatus] = marathonTaskStatus.mesosStatus
  }

  //scalastyle:off cyclomatic.complexity
  def apply(taskStatus: TaskStatus): MarathonTaskStatus = {

    val option = Some(taskStatus)

    taskStatus.getState match {
      case TASK_ERROR => Error(option)
      case TASK_FAILED => Failed(option)
      case TASK_FINISHED => Finished(option)
      case TASK_KILLED => Killed(option)
      case TASK_KILLING => Killing(option)
      case TASK_LOST => if (WontComeBack(taskStatus.getReason)) {
        Gone(option)
      } else if (MightComeBack(taskStatus.getReason)) {
        Unreachable(option)
      } else {
        Lost(option)
      }
      case TASK_RUNNING => Running(option)
      case TASK_STAGING => Staging(option)
      case TASK_STARTING => Starting(option)
    }
  }

  // Marathon specific states
  // RESERVED
  case class Reserved() extends MarathonTaskStatus with NoMesosStatus
  // CREATED
  case class Created() extends MarathonTaskStatus with NoMesosStatus

  // 'Native' mesos TaskStates
  // ERROR
  case class Error(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal
  // FAILED
  case class Failed(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal
  // FINISHED
  case class Finished(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal
  // KILLED
  case class Killed(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal {
    override def killed: Boolean = true
  }
  // KILLING
  case class Killing(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  // LOST
  case class Lost(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal
  // RUNNING
  case class Running(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  // STAGING
  case class Staging(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  // STARTING
  case class Starting(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus

  // Temporarily transformation states, should become mesos native in the future
  // UNREACHABLE
  case class Unreachable(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus
  // GONE
  case class Gone(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal
  // LOST
  // already defined as 'native' mesos state
  // UNKNOWN
  case class Unknown(mesosStatus: Option[TaskStatus]) extends MarathonTaskStatus with Terminal

}
