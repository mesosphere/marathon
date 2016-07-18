package mesosphere.marathon.core.task.state

import org.apache.mesos

// TODO ju handle garbage flag
sealed trait MarathonTaskStatus {
  def toMesosStateName: String = "TASK_" + toString.toUpperCase
}

object MarathonTaskStatus {
  import org.apache.mesos.Protos.TaskState._

  def fromMesosStateName(mesosStateName: String): Option[MarathonTaskStatus] = all.collectFirst {
    case status: MarathonTaskStatus if status.toMesosStateName == mesosStateName => status
  }

  // If we're disconnected at the time of a TASK_LOST event, we will only get the update during
  // a reconciliation. In that case, the specific reason will be shadowed by REASON_RECONCILIATION.
  // Since we don't know the original reason, we need to assume that the task might come back.
  val MightComeBack: Set[mesos.Protos.TaskStatus.Reason] = Set(
    mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION,
    mesos.Protos.TaskStatus.Reason.REASON_SLAVE_DISCONNECTED,
    mesos.Protos.TaskStatus.Reason.REASON_SLAVE_REMOVED
  )

  val WontComeBack: Set[mesos.Protos.TaskStatus.Reason] = {
    mesos.Protos.TaskStatus.Reason.values().toSet.diff(MightComeBack)
  }

  trait Terminal

  //scalastyle:off cyclomatic.complexity
  def apply(taskStatus: mesos.Protos.TaskStatus): MarathonTaskStatus = {
    taskStatus.getState match {
      case TASK_ERROR => Error
      case TASK_FAILED => Failed
      case TASK_FINISHED => Finished
      case TASK_KILLED => Killed
      case TASK_KILLING => Killing
      case TASK_LOST => taskStatus.getReason match {
        case state: mesos.Protos.TaskStatus.Reason if WontComeBack(state) => Gone
        case state: mesos.Protos.TaskStatus.Reason if MightComeBack(taskStatus.getReason) => Unreachable
        case _ => Lost
      }
      case TASK_RUNNING => Running
      case TASK_STAGING => Staging
      case TASK_STARTING => Starting
    }
  }

  /**
    * (Temporarily) mapping of mesos.Protos.TaskStatus.TASK_LOST to MarathonTaskStatus.
    * Should be removed as soon as `Gone`, `Unreachable` and `Lost` are native Mesos task states.
    *
    * @return list of possible mappings of the mesos.Protos.TaskStatus.TASK_LOST
    */
  lazy val mightBeLost: Set[MarathonTaskStatus] = Set(Gone, Unreachable, Lost)

  lazy val all: Set[MarathonTaskStatus] = Set(Reserved, Created, Error, Failed, Finished, Killed, Killing, Lost, //
    Running, Staging, Starting, Unreachable, Gone, Unknown)

  // Marathon specific states
  // RESERVED
  case object Reserved extends MarathonTaskStatus
  // CREATED
  case object Created extends MarathonTaskStatus

  // 'Native' mesos TaskStates
  // ERROR
  case object Error extends MarathonTaskStatus with Terminal
  // FAILED
  case object Failed extends MarathonTaskStatus with Terminal
  // FINISHED
  case object Finished extends MarathonTaskStatus with Terminal
  // KILLED
  case object Killed extends MarathonTaskStatus with Terminal
  // KILLING
  case object Killing extends MarathonTaskStatus
  // LOST
  // Note: At the moment there is no `mesos native` distinguish between `Lost`, `Unreachable`, `Gone` and `Unknown`
  // tl;dr: don't use Lost
  case object Lost extends MarathonTaskStatus with Terminal
  // RUNNING
  case object Running extends MarathonTaskStatus
  // STAGING
  case object Staging extends MarathonTaskStatus
  // STARTING
  case object Starting extends MarathonTaskStatus

  // Temporarily transformation states, should become mesos native in the future
  // UNREACHABLE
  case object Unreachable extends MarathonTaskStatus
  // GONE
  case object Gone extends MarathonTaskStatus with Terminal
  // LOST
  // already defined as 'native' mesos state
  // UNKNOWN
  case object Unknown extends MarathonTaskStatus with Terminal

}
