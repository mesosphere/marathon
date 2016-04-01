package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Internal processor that handles performing all required steps after a task tracker update.
  */
private[tracker] trait TaskTrackerUpdateStepProcessor {
  def process(taskChanged: TaskChanged)(implicit ec: ExecutionContext): Future[Unit]
}
