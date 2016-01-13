package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.PathId
import org.slf4j.LoggerFactory

/**
  * Contains all tasks grouped by app ID.
  */
private[impl] case class AppDataMap private (appTasks: Map[PathId, AppData]) {
  import AppDataMap._

  def updateApp(appId: PathId)(update: AppData => AppData): AppDataMap = {
    val updated = update(appTasks(appId))
    if (updated.shouldRemove) {
      log.info(s"Removed app [$appId] from tracker")
      copy(appTasks = appTasks - appId)
    }
    else {
      log.debug(s"Updated app [$appId], currently ${updated.taskMap.size} tasks in total.")
      copy(appTasks = appTasks + (appId -> updated))
    }
  }

  def getTasks(appId: PathId): Iterable[MarathonTask] = {
    appTasks.get(appId).map(_.tasks).getOrElse(Iterable.empty)
  }

  def getTask(appId: PathId, taskId: String): Option[MarathonTask] = for {
    app <- appTasks.get(appId)
    task <- app.taskMap.get(taskId)
  } yield task

  def toTaskTrackerAppMap: Map[PathId, TaskTracker.App] = {
    appTasks.mapValues(_.toTaskTrackerApp)
  }
}

object AppDataMap {
  private val log = LoggerFactory.getLogger(getClass)

  def of(appTasks: Map[PathId, AppData]): AppDataMap = {
    new AppDataMap(appTasks.withDefault(appId => AppData(appId)))
  }
}

/**
  * Contains only the tasks of the app with the given app ID.
  *
  * @param appId   The id of the app.
  * @param taskMap The tasks of this app by task ID.
  */
private[impl] case class AppData(appId: PathId, taskMap: Map[String, MarathonTask] = Map.empty) {
  def withTask(task: MarathonTask): AppData = {
    copy(taskMap = taskMap + (task.getId -> task))
  }

  def withoutTask(taskId: String): AppData = copy(taskMap = taskMap - taskId)
  def shouldRemove: Boolean = taskMap.isEmpty

  def tasks: Iterable[MarathonTask] = taskMap.values

  def toTaskTrackerApp: TaskTracker.App = TaskTracker.App(appId, taskMap.values)
}
