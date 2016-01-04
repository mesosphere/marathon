package mesosphere.marathon.core.task.tracker.impl
import scala.concurrent.Future

/**
  * Loads all task data into an [[AppDataMap]].
  */
private[tracker] trait TaskLoader {
  def loadTasks(): Future[AppDataMap]
}
