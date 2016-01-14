package mesosphere.marathon.core.task.tracker

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.PathId
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

/**
  * The TaskTracker exposes the latest known state for every task.
  *
  * It is an read-only interface. For modification, see
  * * [[TaskCreationHandler]] for creating/removing tasks
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

  def list: TaskTracker.AppDataMap
  def listAsync()(implicit ec: ExecutionContext): Future[TaskTracker.AppDataMap]

  def count(appId: PathId): Int
  def countAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Int]

  def contains(appId: PathId): Boolean
  def containsAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean]
}

object TaskTracker {
  /**
    * Contains all tasks grouped by app ID.
    */
  case class AppDataMap private (appTasks: Map[PathId, TaskTracker.App]) {
    import AppDataMap._

    def keySet: Set[PathId] = appTasks.keySet
    def contains(appId: PathId): Boolean = appTasks.contains(appId)

    def getTasks(appId: PathId): Iterable[MarathonTask] = {
      appTasks.get(appId).map(_.tasks).getOrElse(Iterable.empty)
    }

    def getTask(appId: PathId, taskId: String): Option[MarathonTask] = for {
      app <- appTasks.get(appId)
      task <- app.taskMap.get(taskId)
    } yield task

    def tasks: Iterable[MarathonTask] = appTasks.view.flatMap(_._2.tasks)

    private[tracker] def updateApp(appId: PathId)(update: TaskTracker.App => TaskTracker.App): AppDataMap = {
      val updated = update(appTasks(appId))
      if (updated.isEmpty) {
        log.info(s"Removed app [$appId] from tracker")
        copy(appTasks = appTasks - appId)
      }
      else {
        log.debug(s"Updated app [$appId], currently ${updated.taskMap.size} tasks in total.")
        copy(appTasks = appTasks + (appId -> updated))
      }
    }
  }

  object AppDataMap {
    private val log = LoggerFactory.getLogger(getClass)

    def of(appTasks: collection.immutable.Map[PathId, TaskTracker.App]): AppDataMap = {
      new AppDataMap(appTasks.withDefault(appId => TaskTracker.App(appId)))
    }

    def of(apps: TaskTracker.App*): AppDataMap = of(Map(apps.map(app => app.appId -> app): _*))

    def empty: AppDataMap = AppDataMap(Map.empty)
  }
  /**
    * Contains only the tasks of the app with the given app ID.
    *
    * @param appId   The id of the app.
    * @param taskMap The tasks of this app by task ID.
    */
  case class App(appId: PathId, taskMap: Map[String, MarathonTask] = Map.empty) {
    def isEmpty: Boolean = taskMap.isEmpty
    def contains(taskId: String): Boolean = taskMap.contains(taskId)
    def tasks: Iterable[MarathonTask] = taskMap.values

    private[tracker] def withTask(task: MarathonTask): App = {
      copy(taskMap = taskMap + (task.getId -> task))
    }

    private[tracker] def withoutTask(taskId: String): App = copy(taskMap = taskMap - taskId)
  }

  object App {
    def apply(appId: PathId, tasks: Iterable[MarathonTask]): App =
      App(appId, tasks.map(task => task.getId -> task).toMap)
  }
}
