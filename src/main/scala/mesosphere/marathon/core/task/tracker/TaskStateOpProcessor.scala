package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.instance.InstanceStateOp
import mesosphere.marathon.core.task.{ TaskStateChange }

import scala.concurrent.Future

/**
  * Handles the processing of TaskStateOps. These might originate from
  * * Creating a task
  * * Updating a task (due to a state change, a timeout, a mesos update)
  * * Expunging a task
  */
trait TaskStateOpProcessor {
  /** Process a TaskStateOp and propagate its result. */
  def process(stateOp: InstanceStateOp): Future[TaskStateChange]
}
