package mesosphere.marathon.core.task

import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.state.Timestamp

sealed trait TaskStateOp

object TaskStateOp {
  case class Launch(appVersion: Timestamp, status: Task.Status, networking: Task.Networking) extends TaskStateOp
  case class MesosUpdate(status: MarathonTaskStatus, now: Timestamp) extends TaskStateOp
  case object ReservationTimeout extends TaskStateOp
}

sealed trait TaskStateChange

object TaskStateChange {
  case class Update(updatedTask: Task) extends TaskStateChange
  case object Expunge extends TaskStateChange
  case object NoChange extends TaskStateChange
  case class Failure(cause: Throwable) extends TaskStateChange
  object Failure {
    def apply(message: String): Failure = Failure(TaskStateChangeException(message))
  }
}

case class TaskStateChangeException(message: String) extends Exception(message)
