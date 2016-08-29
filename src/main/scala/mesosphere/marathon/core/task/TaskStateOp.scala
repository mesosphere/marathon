package mesosphere.marathon.core.task

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.TaskStateChange.{ Expunge, Update }
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.collection.immutable.Seq

sealed trait TaskStateOp {
  def taskId: Instance.Id
  /**
    * The possible task state if processing the state op succeeds. If processing the
    * state op fails, this state will never be persisted, so be cautious when using it.
    */
  def possibleNewState: Option[Instance] = None
}

object TaskStateOp {
  /** Launch (aka create) an ephemeral task*/
  // FIXME (3221): The type should be LaunchedEphemeral but that needs a lot of test adjustments
  case class LaunchEphemeral(task: Task) extends TaskStateOp {
    override def taskId: Instance.Id = task.id
    override def possibleNewState: Option[Instance] = Some(task)
  }

  /** Revert a task to the given state. Used in case TaskOps are rejected. */
  case class Revert(task: Task) extends TaskStateOp {
    override def taskId: Instance.Id = task.id
    override def possibleNewState: Option[Instance] = Some(task)
  }

  case class Reserve(task: Task.Reserved) extends TaskStateOp {
    override def taskId: Instance.Id = task.id
    override def possibleNewState: Option[Instance] = Some(task)
  }

  case class LaunchOnReservation(
    taskId: Instance.Id,
    runSpecVersion: Timestamp,
    status: Task.Status,
    hostPorts: Seq[Int]) extends TaskStateOp

  case class MesosUpdate(task: Task, status: MarathonTaskStatus,
      mesosStatus: mesos.Protos.TaskStatus, now: Timestamp) extends TaskStateOp {
    override def taskId: Instance.Id = task.id
  }

  object MesosUpdate {
    def apply(task: Task, mesosStatus: mesos.Protos.TaskStatus, now: Timestamp): MesosUpdate = {
      MesosUpdate(task, MarathonTaskStatus(mesosStatus), mesosStatus, now)
    }
  }

  case class ReservationTimeout(taskId: Instance.Id) extends TaskStateOp

  /** Expunge a task whose TaskOp was rejected */
  case class ForceExpunge(taskId: Instance.Id) extends TaskStateOp
}

sealed trait TaskStateChange

object TaskStateChange {
  case class Update(newState: Task, oldState: Option[Task]) extends TaskStateChange
  case class Expunge(task: Task) extends TaskStateChange
  case class NoChange(taskId: Instance.Id) extends TaskStateChange
  case class Failure(cause: Throwable) extends TaskStateChange
  object Failure {
    def apply(message: String): Failure = Failure(TaskStateChangeException(message))
  }
}

object EffectiveTaskStateChange {
  def unapply(stateChange: TaskStateChange): Option[Task] = stateChange match {
    case Update(newState, _) => Some(newState)
    case Expunge(oldState) => Some(oldState)
    case _ => None
  }
}

case class TaskStateChangeException(message: String) extends Exception(message)
