package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.tracker.InstanceTracker

import scala.concurrent.Future

/**
  * Loads all task data into an [[InstanceTracker.InstancesBySpec]].
  */
private[tracker] trait InstancesLoader {
  def load(): Future[InstanceTracker.InstancesBySpec]
}
