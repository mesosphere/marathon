package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.instance.update.InstanceUpdateOperation

import scala.concurrent.Future

/**
  * Reacts to task timeouts.
  */
// TODO(PODS): rename to InstanceReservationTimeoutHandler
trait TaskReservationTimeoutHandler {
  def timeout(op: InstanceUpdateOperation.ReservationTimeout): Future[_]
}
