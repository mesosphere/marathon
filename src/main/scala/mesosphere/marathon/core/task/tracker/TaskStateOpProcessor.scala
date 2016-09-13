package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }

import scala.concurrent.Future

/**
  * Handles the processing of [[InstanceUpdateOperation]]s. These might originate from
  * * Creating an instance
  * * Updating an instance (due to a state change, a timeout, a mesos update)
  * * Expunging an instance
  */
trait TaskStateOpProcessor {
  /** Process an [[InstanceUpdateOperation]] and propagate its result. */
  def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect]
}
