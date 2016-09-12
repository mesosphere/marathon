package mesosphere.marathon.core.task.tracker.impl

import akka.stream.Materializer
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.TaskRepository
import mesosphere.marathon.stream.Sink
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * Loads all task data into an [[InstanceTracker.InstancesBySpec]] from a [[TaskRepository]].
  */
private[tracker] class InstancesLoaderImpl(repo: TaskRepository)(implicit val mat: Materializer)
    extends InstancesLoader {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  override def loadTasks(): Future[InstanceTracker.InstancesBySpec] = {
    for {
      names <- repo.ids().runWith(Sink.seq)
      _ = log.info(s"About to load ${names.size} tasks")
      tasks <- Future.sequence(names.map(repo.get)).map(_.flatten)
    } yield {
      log.info(s"Loaded ${tasks.size} tasks")
      val tasksByApp = tasks.groupBy(_.taskId.runSpecId)
      val map = tasksByApp.map {
        case (appId, appTasks) => appId -> // TODO PODs build Instances!
          InstanceTracker.SpecInstances.forInstances(appId, appTasks.map(task => Instance(task)))
      }
      InstanceTracker.InstancesBySpec.of(map)
    }
  }
}
