package mesosphere.marathon.tasks
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

/**
  * Notifies the [[TaskTracker]] of task creation and termination.
  */
trait TaskCreator {
  /**
    * Create a new task.
    *
    * If the task exists already, the returned Future will fail.
    */
  def created(appId: PathId, task: MarathonTask): Future[MarathonTask]

  /**
    * Remove the task for the given app with the given ID completely.
    *
    * If the task does not exist, the returned Future will not fail.
    */
  def terminated(appId: PathId, taskId: String): Future[_]
}
