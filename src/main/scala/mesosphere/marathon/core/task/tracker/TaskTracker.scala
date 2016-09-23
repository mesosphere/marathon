package mesosphere.marathon
package core.task.tracker

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

/**
  * The TaskTracker exposes the latest known state for every task.
  *
  * It is an read-only interface. For modification, see
  * * [[TaskStateOpProcessor]] for create, update, delete operations
  *
  * FIXME: To allow introducing the new asynchronous [[TaskTracker]] without needing to
  * refactor a lot of code at once, synchronous methods are still available but should be
  * avoided in new code.
  */
trait TaskTracker {

  def appTasksLaunchedSync(appId: PathId): Seq[Task]
  def appTasksSync(appId: PathId): Seq[Task]
  def appTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Seq[Task]]

  def task(taskId: Task.Id): Future[Option[Task]]

  def tasksByAppSync: TaskTracker.TasksByApp
  def tasksByApp()(implicit ec: ExecutionContext): Future[TaskTracker.TasksByApp]

  def countLaunchedAppTasksSync(appId: PathId): Int
  def countLaunchedAppTasksSync(appId: PathId, filter: Task => Boolean): Int
  def countAppTasksSync(appId: PathId): Int
  def countAppTasksSync(appId: PathId, filter: Task => Boolean): Int
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

    def appTasks(appId: PathId): Seq[Task] = {
      appTasksMap.get(appId).map(_.tasks).getOrElse(Seq.empty)
    }

    def task(taskId: Task.Id): Option[Task] = for {
      app <- appTasksMap.get(taskId.runSpecId)
      task <- app.taskMap.get(taskId)
    } yield task

    def allTasks: Seq[Task] = appTasksMap.values.flatMap(_.tasks)(collection.breakOut)

    private[tracker] def updateApp(appId: PathId)(update: TaskTracker.AppTasks => TaskTracker.AppTasks): TasksByApp = {
      val updated = update(appTasksMap(appId))
      if (updated.isEmpty) {
        log.info(s"Removed app [$appId] from tracker")
        copy(appTasksMap = appTasksMap - appId)
      } else {
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

    def forTasks(tasks: Task*): TasksByApp = of(
      tasks.groupBy(_.runSpecId).map { case (appId, appTasks) => appId -> AppTasks.forTasks(appId, appTasks.to[Seq]) }
    )

    def empty: TasksByApp = of(collection.immutable.Map.empty[PathId, TaskTracker.AppTasks])
  }
  /**
    * Contains only the tasks of the app with the given app ID.
    *
    * @param appId   The id of the app.
    * @param taskMap The tasks of this app by task ID. FIXME: change keys to Task.TaskID
    */
  case class AppTasks(appId: PathId, taskMap: Map[Task.Id, Task] = Map.empty) {

    lazy val isEmpty: Boolean = taskMap.isEmpty
    def contains(taskId: Task.Id): Boolean = taskMap.contains(taskId)
    lazy val tasks: Seq[Task] = taskMap.values.to[Seq]

    private[tracker] def withTask(task: Task): AppTasks = copy(taskMap = taskMap + (task.taskId -> task))

    private[tracker] def withoutTask(taskId: Task.Id): AppTasks = copy(taskMap = taskMap - taskId)
  }

  object AppTasks {
    def forTasks(appId: PathId, tasks: Seq[Task]): AppTasks =
      AppTasks(appId, tasks.map(task => task.taskId -> task)(collection.breakOut))
  }
}
