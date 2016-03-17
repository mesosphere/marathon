package mesosphere.marathon.core.task

import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.state.Timestamp

sealed trait TaskStateOp {
  def taskId: Task.Id
  /**
    * The possible task state if processing the state op succeeds. If processing the
    * state op fails, this state will never be persisted, so be cautious when using it.
    */
  def possibleNewState: Option[Task] = None
}

object TaskStateOp {
  // FIXME (3221): Create is used for both launching an ephemeral task, and
  // re-creating the oldTask state in case an update failed – this is not
  // very obvious and should be improved.
  case class Create(task: Task) extends TaskStateOp {
    override def taskId: Id = task.taskId
    override def possibleNewState: Option[Task] = Some(task)
  }

  case class Reserve(task: Task.Reserved) extends TaskStateOp {
    override def taskId: Id = task.taskId
    override def possibleNewState: Option[Task] = Some(task)
  }

  case class LaunchOnReservation(
    taskId: Task.Id,
    appVersion: Timestamp,
    status: Task.Status,
    networking: Task.Networking) extends TaskStateOp

  case class MesosUpdate(taskId: Task.Id, status: MarathonTaskStatus, now: Timestamp) extends TaskStateOp

  case class ReservationTimeout(taskId: Task.Id) extends TaskStateOp

  /**
    * If a taskOp introduced a new task but was not accepted afterwards, it will be reverted
    * using this TaskOp.
    */
  case class ForceExpunge(taskId: Task.Id) extends TaskStateOp
}

sealed trait TaskStateChange

object TaskStateChange {
  case class Update(task: Task, oldTask: Option[Task]) extends TaskStateChange
  case class Expunge(task: Task) extends TaskStateChange
  case class NoChange(taskId: Task.Id) extends TaskStateChange
  case class Failure(cause: Throwable) extends TaskStateChange
  object Failure {
    def apply(message: String): Failure = Failure(TaskStateChangeException(message))
  }
}

case class TaskStateChangeException(message: String) extends Exception(message)
