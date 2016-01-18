package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.TaskRepository
import mesosphere.marathon.tasks.TaskIdUtil
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Loads all task data into an [[TaskTracker.AppDataMap]] from a [[TaskRepository]].
  */
private[tracker] class TaskLoaderImpl(repo: TaskRepository) extends TaskLoader {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  override def loadTasks(): Future[TaskTracker.AppDataMap] = {
    for {
      names <- repo.allIds()
      _ = log.info(s"About to load ${names.size} tasks")
      tasks <- Future.sequence(names.map(repo.task(_))).map(_.flatten)
    } yield {
      log.info(s"Loaded ${tasks.size} tasks")
      val tasksByApp = tasks.groupBy(task => TaskIdUtil.appId(task.getId))
      val map = tasksByApp.iterator.map {
        case (appId, appTasks) =>
          appId -> TaskTracker.App(appId, appTasks.map(task => task.getId -> task).toMap)
      }.toMap
      TaskTracker.AppDataMap.of(map)
    }
  }
}
