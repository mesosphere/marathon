package mesosphere.marathon.state

import akka.{ Done, NotUsed }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.repository.TaskRepository
import mesosphere.marathon.metrics.Metrics

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TaskEntityRepository(
  protected[state] val store: EntityStore[MarathonTaskState],
  protected val metrics: Metrics)(implicit mat: Materializer)
    extends EntityRepository[MarathonTaskState] with TaskRepository {

  val maxVersions = None

  def get(key: String): Future[Option[MarathonTask]] = currentVersion(key).map {
    case Some(taskState) => Some(taskState.toProto)
    case _ => None
  }

  def store(task: MarathonTask): Future[Done] = {
    this.store.store(task.getId, MarathonTaskState(task)).map(_ => Done)
  }

  def ids(): Source[String, NotUsed] = Source.fromFuture(allIds()).mapConcat(identity)

  def all(): Source[MarathonTask, NotUsed] =
    ids().mapAsync(Int.MaxValue)(get).filter(_.isDefined).map(_.get)

  def delete(id: String): Future[Done] = expunge(id).map(_ => Done)
}

object TaskEntityRepository {
  val storePrefix = "task:"
}
