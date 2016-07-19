package mesosphere.marathon.state

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.AppRepository
import mesosphere.marathon.metrics.Metrics

import scala.async.Async.{ async, await }
import scala.concurrent.Future

class AppEntityRepository(
  val store: EntityStore[AppDefinition],
  val maxVersions: Option[Int] = None,
  val metrics: Metrics)
    extends EntityRepository[AppDefinition] with AppRepository {
  import scala.concurrent.ExecutionContext.Implicits.global

  def ids(): Source[PathId, NotUsed] = {
    val ids = async {
      await(allIds()).map(PathId.fromSafePath)(collection.breakOut)
    }
    Source.fromFuture(ids).mapConcat(identity)
  }

  def get(appId: PathId): Future[Option[AppDefinition]] = currentVersion(appId.safePath)
  def versions(appId: PathId): Source[OffsetDateTime, NotUsed] =
    Source.fromFuture(listVersions(appId.safePath)).mapConcat(identity).map(_.toOffsetDateTime)

  def delete(appId: PathId): Future[Done] = expunge(appId.safePath).map(_ => Done)

  def get(appId: PathId, version: OffsetDateTime): Future[Option[AppDefinition]] =
    app(appId, Timestamp(version))

  def app(appId: PathId, version: Timestamp): Future[Option[AppDefinition]] =
    entity(appId.safePath, version)

  def store(id: PathId, appDefinition: AppDefinition): Future[Done] = store(appDefinition)


  override def store(id: PathId, appDef: AppDefinition, version: OffsetDateTime): Future[Done] =
    storeWithVersion(id.safePath, Timestamp(version), appDef).map(_ => Done)

  def all(): Source[AppDefinition, NotUsed] =
    Source.fromFuture(current()).mapConcat(identity)
}
