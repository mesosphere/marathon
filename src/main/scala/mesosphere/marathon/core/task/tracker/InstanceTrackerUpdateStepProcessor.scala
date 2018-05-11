package mesosphere.marathon
package core.task.tracker

import akka.Done
import mesosphere.marathon.core.instance.update.InstanceChange

import scala.concurrent.{ExecutionContext, Future}

/**
  * Internal processor that handles performing all required steps after a task tracker update.
  */
private[tracker] trait InstanceTrackerUpdateStepProcessor {
  def process(change: InstanceChange)(implicit ec: ExecutionContext): Future[Done]
}
