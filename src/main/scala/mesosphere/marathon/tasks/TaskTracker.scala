package mesosphere.marathon.tasks
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ Timestamp, PathId }

import scala.collection.{ Iterable, Map }

/**
  * The TaskTracker exposes the latest known state for every task.
  *
  * It is an read-only interface. For modification, see
  * * [[TaskCreator]] for creating/removing tasks
  * * [[TaskUpdater]] for updating a task state according to a status update
  * * [[TaskReconciler]] for querying tasks and removing unknown apps
  */
trait TaskTracker {

  def getTasks(appId: PathId): Iterable[MarathonTask]

  def getTask(appId: PathId, taskId: String): Option[MarathonTask]

  def getVersion(appId: PathId, taskId: String): Option[Timestamp]

  def list: Map[PathId, TaskTracker.App]

  def count(appId: PathId): Int

  def contains(appId: PathId): Boolean
}

object TaskTracker {
  case class App(appName: PathId, tasks: Iterable[MarathonTask], shutdown: Boolean)
}
