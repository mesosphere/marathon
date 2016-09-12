package mesosphere.marathon.core.task.tracker

import akka.Done
import mesosphere.marathon.core.task.InstanceStateOp

import scala.concurrent.Future

/**
  * Notifies the [[InstanceTracker]] of instance creation and termination.
  */
trait InstanceCreationHandler {
  /**
    * Create a new instance.
    *
    * If the instance exists already, the existing instance will be overwritten so make sure
    * that you generate unique IDs.
    */
  def created(stateOp: InstanceStateOp): Future[Done]

  /**
    * Remove the instance for the given app/pod with the given ID completely.
    *
    * If the instance does not exist, the returned Future will not fail.
    */
  def terminated(stateOp: InstanceStateOp.ForceExpunge): Future[Done]
}
