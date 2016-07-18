package mesosphere.marathon.state

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.TaskRepository
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.Id
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.util.CallerThreadExecutionContext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TaskEntityRepository(
  protected[state] val store: EntityStore[MarathonTaskState],
  protected val metrics: Metrics)(implicit mat: Materializer)
    extends EntityRepository[MarathonTaskState] with TaskRepository {

  val maxVersions = None

  def get(id: Id): Future[Option[Task]] = currentVersion(id.idString).map {
    case Some(taskState) => Some(TaskSerializer.fromProto(taskState.toProto))
    case _ => None
  }

  def store(id: Id, task: Task): Future[Done] =
    storeByName(id.idString, MarathonTaskState(TaskSerializer.toProto(task)))
      .map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  override def delete(id: Id): Future[Done] =
    expunge(id.idString).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  def ids(): Source[Task.Id, NotUsed] = Source.fromFuture(allIds()).mapConcat(identity).map(Task.Id(_))

  def all(): Source[Task, NotUsed] =
    Source.fromFuture(current()).mapConcat(identity).map(state => TaskSerializer.fromProto(state.toProto))
}

object TaskEntityRepository {
  val storePrefix = "task:"
}
