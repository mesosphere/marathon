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
  * refactor a lot of code at once, synchronous methods are still available but should be
  * avoided in new code.
  */
trait TaskTracker {

  def appTasksSync(appId: PathId): Iterable[MarathonTask]
  def appTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Iterable[MarathonTask]]

  def taskSync(appId: PathId, taskId: String): Option[MarathonTask]
  def task(appId: PathId, taskId: String)(implicit ec: ExecutionContext): Future[Option[MarathonTask]]

  def tasksByAppSync: TaskTracker.TasksByApp
  def tasksByApp()(implicit ec: ExecutionContext): Future[TaskTracker.TasksByApp]

  def countAppTasksSync(appId: PathId): Int
  def countAppTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Int]

  def hasAppTasksSync(appId: PathId): Boolean
  def hasAppTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean]
}

object TaskTracker {
  /**
    * Contains all tasks grouped by app ID.
    */
  case class TasksByApp private (appTasksMap: Map[PathId, TaskTracker.AppTasks]) {
    import TasksByApp._

    def allAppIdsWithTasks: Set[PathId] = appTasksMap.keySet

    def hasAppTasks(appId: PathId): Boolean = appTasksMap.contains(appId)

    def appTasks(appId: PathId): Iterable[MarathonTask] = {
      appTasksMap.get(appId).map(_.tasks).getOrElse(Iterable.empty)
    }

    def task(appId: PathId, taskId: String): Option[MarathonTask] = for {
      app <- appTasksMap.get(appId)
      task <- app.taskMap.get(taskId)
    } yield task

    def allTasks: Iterable[MarathonTask] = appTasksMap.values.view.flatMap(_.tasks)

    private[tracker] def updateApp(appId: PathId)(update: TaskTracker.AppTasks => TaskTracker.AppTasks): TasksByApp = {
      val updated = update(appTasksMap(appId))
      if (updated.isEmpty) {
        log.info(s"Removed app [$appId] from tracker")
        copy(appTasksMap = appTasksMap - appId)
      }
      else {
        log.debug(s"Updated app [$appId], currently ${updated.taskMap.size} tasks in total.")
        copy(appTasksMap = appTasksMap + (appId -> updated))
      }
    }
  }

  object TasksByApp {
    private val log = LoggerFactory.getLogger(getClass)

    def of(appTasks: collection.immutable.Map[PathId, TaskTracker.AppTasks]): TasksByApp = {
      new TasksByApp(appTasks.withDefault(appId => TaskTracker.AppTasks(appId)))
    }

    def of(apps: TaskTracker.AppTasks*): TasksByApp = of(Map(apps.map(app => app.appId -> app): _*))

    def empty: TasksByApp = TasksByApp(Map.empty)
  }
  /**
    * Contains only the tasks of the app with the given app ID.
    *
    * @param appId   The id of the app.
    * @param taskMap The tasks of this app by task ID.
    */
  case class AppTasks(appId: PathId, taskMap: Map[String, MarathonTask] = Map.empty) {
    def isEmpty: Boolean = taskMap.isEmpty
    def contains(taskId: String): Boolean = taskMap.contains(taskId)
    def tasks: Iterable[MarathonTask] = taskMap.values

    private[tracker] def withTask(task: MarathonTask): AppTasks = {
      copy(taskMap = taskMap + (task.getId -> task))
    }

    private[tracker] def withoutTask(taskId: String): AppTasks = copy(taskMap = taskMap - taskId)
  }

  object AppTasks {
    def apply(appId: PathId, tasks: Iterable[MarathonTask]): AppTasks =
      AppTasks(appId, tasks.map(task => task.getId -> task).toMap)
  }
}
