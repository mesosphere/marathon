package mesosphere.marathon.core.task

import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.state.Timestamp

sealed trait TaskStateOp

object TaskStateOp {
  case class Launch(appVersion: Timestamp, status: Task.Status, networking: Task.Networking) extends TaskStateOp
  // FIXME (3221): MarathonTaskStatus was introduced for the message bus – is it ok to use it here?
  case class MesosUpdate(status: MarathonTaskStatus, now: Timestamp) extends TaskStateOp
  case object Timeout extends TaskStateOp
  //  Garbage?
}

sealed trait TaskStateChange

object TaskStateChange {
  case class Update(task: Task) extends TaskStateChange
  case object Expunge extends TaskStateChange
  case object NoChange extends TaskStateChange
  case class Failure(cause: String) extends TaskStateChange
}
