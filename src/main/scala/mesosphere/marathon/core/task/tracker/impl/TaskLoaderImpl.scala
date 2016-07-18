package mesosphere.marathon.core.task.tracker.impl

import akka.stream.Materializer
import mesosphere.marathon.core.storage.repository.TaskRepository
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.stream.Sink
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Loads all task data into an [[TaskTracker.TasksByApp]] from a [[TaskRepository]].
  */
private[tracker] class TaskLoaderImpl(repo: TaskRepository)(implicit val mat: Materializer) extends TaskLoader {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  override def loadTasks(): Future[TaskTracker.TasksByApp] = {
    for {
      names <- repo.ids().runWith(Sink.seq)
      _ = log.info(s"About to load ${names.size} tasks")
      tasks <- Future.sequence(names.map(repo.get)).map(_.flatten)
    } yield {
      log.info(s"Loaded ${tasks.size} tasks")
      val deserializedTasks = tasks.map(TaskSerializer.fromProto)
      val tasksByApp = deserializedTasks.groupBy(_.taskId.runSpecId)
      val map = tasksByApp.iterator.map {
        case (appId, appTasks) => appId -> TaskTracker.AppTasks.forTasks(appId, appTasks)
      }.toMap
      TaskTracker.TasksByApp.of(map)
    }
  }
}
