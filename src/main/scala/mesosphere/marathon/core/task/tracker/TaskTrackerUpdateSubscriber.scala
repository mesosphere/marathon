package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.core.task.TaskStateChange

import scala.concurrent.Future

/**
  * Subscribes to task state changes from the TaskTracker
  */
trait TaskTrackerUpdateSubscriber {
  def handleTaskStateChange(event: TaskStateChange): Future[Unit]
}
