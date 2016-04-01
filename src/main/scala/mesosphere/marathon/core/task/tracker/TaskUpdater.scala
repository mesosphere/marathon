package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

/**
  * Notifies the [[TaskTracker]] of task updates.
  */
// FIXME (3221): This trait is superseded by TaskStateOpProcessor and can be removed
trait TaskUpdater {
  /**
    * Process a status update for an existing task and either updates the tasks or removes
    * it from the TaskTracker.
    *
    * If the task does not exist yet, the returned Future will fail.
    */
  def statusUpdate(appId: PathId, status: TaskStatus): Future[_]
}
