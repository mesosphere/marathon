package mesosphere.marathon.state

import java.time.OffsetDateTime

import akka.{ Done, NotUsed }
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.storage.repository.TaskFailureRepository
import mesosphere.marathon.metrics.Metrics
import mesosphere.util.CallerThreadExecutionContext

import scala.concurrent.Future

/**
  * Stores the last TaskFailure per app id.
  */
class TaskFailureEntityRepository(
  protected val store: EntityStore[TaskFailure],
  protected val maxVersions: Option[Int] = Some(1),
  protected val metrics: Metrics)
    extends EntityRepository[TaskFailure] with TaskFailureRepository {

  def ids(): Source[PathId, NotUsed] =
    Source.fromFuture(allIds()).mapConcat(identity).map(PathId.fromSafePath)

  def versions(id: PathId): Source[OffsetDateTime, NotUsed] =
    Source.fromFuture(listVersions(id.safePath)).mapConcat(identity).map(_.toOffsetDateTime)

  def get(id: PathId): Future[Option[TaskFailure]] = currentVersion(id.safePath)

  def get(id: PathId, version: OffsetDateTime): Future[Option[TaskFailure]] =
    entity(id.safePath, Timestamp(version))

  def store(id: PathId, value: TaskFailure): Future[Done] =
    storeByName(id.safePath, value).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)

  def delete(id: PathId): Future[Done] =
    expunge(id.safePath).map(_ => Done)(CallerThreadExecutionContext.callerThreadExecutionContext)
}
