package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.task.InstanceStateOp

import scala.concurrent.Future

/**
  * Reacts to task timeouts.
  */
trait TaskReservationTimeoutHandler {
  def timeout(op: InstanceStateOp.ReservationTimeout): Future[_]
}
