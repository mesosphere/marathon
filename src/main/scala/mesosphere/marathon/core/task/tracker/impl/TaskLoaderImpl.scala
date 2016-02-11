package mesosphere.marathon.core.task.tracker.impl

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.TaskRepository
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Loads all task data into an [[TaskTracker.TasksByApp]] from a [[TaskRepository]].
  */
private[tracker] class TaskLoaderImpl(repo: TaskRepository) extends TaskLoader {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  override def loadTasks(): Future[TaskTracker.TasksByApp] = {
    for {
      names <- repo.allIds()
      _ = log.info(s"About to load ${names.size} tasks")
      tasks <- Future.sequence(names.map(repo.task(_))).map(_.flatten)
    } yield {
      log.info(s"Loaded ${tasks.size} tasks")
      val tasksByApp = tasks.groupBy(task => Task.Id(task.getId).appId)
      val map = tasksByApp.iterator.map {
        case (appId, appTasks) => appId -> TaskTracker.AppTasks(appId, appTasks)
      }.toMap
      TaskTracker.TasksByApp.of(map)
    }
  }
}
