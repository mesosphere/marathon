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

  def allPathIds(): Source[PathId, NotUsed] = {
    val ids = async {
      await(allIds()).map(PathId.fromSafePath)(collection.breakOut)
    }
    Source.fromFuture(ids).mapConcat(identity)
  }

  def currentVersion(appId: PathId): Future[Option[AppDefinition]] = currentVersion(appId.safePath)
  def listVersions(appId: PathId): Source[OffsetDateTime, NotUsed] =
    Source.fromFuture(listVersions(appId.safePath)).mapConcat(identity).map(_.toOffsetDateTime)

  def expunge(appId: PathId): Future[Done] = expunge(appId.safePath).map(_ => Done)

  def app(appId: PathId, version: OffsetDateTime): Future[Option[AppDefinition]] =
    app(appId, Timestamp(version))

  def app(appId: PathId, version: Timestamp): Future[Option[AppDefinition]] =
    entity(appId.safePath, version)

  def store(appDef: AppDefinition): Future[Done] =
    storeWithVersion(appDef.id.safePath, appDef.version, appDef).map(_ => Done)

  def apps(): Source[AppDefinition, NotUsed] =
    Source.fromFuture(current()).mapConcat(identity)
}
