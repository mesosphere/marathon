package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.task.TaskStateOp

import scala.concurrent.Future

/**
  * Reacts to task timeouts.
  */
trait TaskReservationTimeoutHandler {
  def timeout(op: TaskStateOp.ReservationTimeout): Future[_]
}
