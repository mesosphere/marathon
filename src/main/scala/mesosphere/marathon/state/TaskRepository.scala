package mesosphere.marathon.state

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.metrics.Metrics
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

class TaskRepository(
  protected[state] val store: EntityStore[MarathonTaskState],
  protected val metrics: Metrics)
    extends EntityRepository[MarathonTaskState] {

  val maxVersions = None

  def task(key: String): Future[Option[MarathonTask]] = currentVersion(key).map {
    case Some(taskState) => Some(taskState.toProto)
    case _               => None
  }

  def tasksKeys(appId: PathId): Future[Iterable[String]] = {
    allIds().map(_.filter(name => name.startsWith(appId.safePath)))
  }

  def store(task: MarathonTask): Future[MarathonTask] = {
    this.store.store(task.getId, MarathonTaskState(task)).map(_.toProto)
  }
}

object TaskRepository {
  val storePrefix = "task:"
}
