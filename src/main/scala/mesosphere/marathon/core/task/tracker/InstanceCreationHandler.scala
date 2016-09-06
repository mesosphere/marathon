package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.instance.InstanceStateOp

import scala.concurrent.Future

/**
  * Notifies the [[InstanceTracker]] of task creation and termination.
  */
trait InstanceCreationHandler {
  /**
    * Create a new task.
    *
    * If the task exists already, the existing task will be overwritten so make sure
    * that you generate unique IDs.
    */
  def created(taskStateOp: InstanceStateOp): Future[Unit]

  /**
    * Remove the task for the given app with the given ID completely.
    *
    * If the task does not exist, the returned Future will not fail.
    */
  def terminated(taskStateOp: InstanceStateOp.ForceExpunge): Future[_]
}
