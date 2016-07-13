package mesosphere.marathon.core.task.state

// TODO ju organize imports
import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.Protos.TaskStatus.Reason._

// TODO ju handle garbage flag
sealed trait MarathonTaskStatus

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

  trait Terminal {
    def killed: Boolean = false
  }

  //scalastyle:off cyclomatic.complexity
  def apply(taskStatus: TaskStatus): MarathonTaskStatus = {
    taskStatus.getState match {
      case TASK_ERROR => Error
      case TASK_FAILED => Failed
      case TASK_FINISHED => Finished
      case TASK_KILLED => Killed
      case TASK_KILLING => Killing
      case TASK_LOST => taskStatus.getReason match {
        case state: TaskStatus.Reason if WontComeBack(state) => Gone
        case state: TaskStatus.Reason if MightComeBack(taskStatus.getReason) => Unreachable
        case _ => Lost
      }
      case TASK_RUNNING => Running
      case TASK_STAGING => Staging
      case TASK_STARTING => Starting
    }
  }

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
  case object Killed extends MarathonTaskStatus with Terminal {
    override def killed: Boolean = true
  }
  // KILLING
  case object Killing extends MarathonTaskStatus
  // LOST
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
