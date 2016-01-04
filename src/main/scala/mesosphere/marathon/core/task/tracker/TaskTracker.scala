package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.PathId

import scala.collection.{ Iterable, Map }
import scala.concurrent.{ ExecutionContext, Future }

/**
  * The TaskTracker exposes the latest known state for every task.
  *
  * It is an read-only interface. For modification, see
  * * [[TaskCreator]] for creating/removing tasks
  * * [[TaskUpdater]] for updating a task state according to a status update
  *
  * FIXME: To allow introducing the new asynchronous [[TaskTracker]] without needing to
  * refactor a lot of code at once, synchronous methods are still available.
  */
trait TaskTracker {

  def getTasks(appId: PathId): Iterable[MarathonTask]
  def getTasksAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Iterable[MarathonTask]]

  def getTask(appId: PathId, taskId: String): Option[MarathonTask]
  def getTaskAsync(appId: PathId, taskId: String)(implicit ec: ExecutionContext): Future[Option[MarathonTask]]

  def list: Map[PathId, TaskTracker.App]
  def listAsync()(implicit ec: ExecutionContext): Future[Map[PathId, TaskTracker.App]]

  def count(appId: PathId): Int
  def countAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Int]

  def contains(appId: PathId): Boolean
  def containsAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean]
}

object TaskTracker {
  case class App(appName: PathId, tasks: Iterable[MarathonTask])
}
