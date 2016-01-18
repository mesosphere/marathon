package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.tracker.TaskTracker

import scala.concurrent.Future

/**
  * Loads all task data into an [[TaskTracker.TasksByApp]].
  */
private[tracker] trait TaskLoader {
  def loadTasks(): Future[TaskTracker.TasksByApp]
}
