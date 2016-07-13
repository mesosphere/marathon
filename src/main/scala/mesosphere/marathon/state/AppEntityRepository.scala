package mesosphere.marathon.state

import java.time.OffsetDateTime

import akka.{ Done, NotUsed }
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.storage.repository.{ AppVersion, AppRepository => CoreAppRepository }
import mesosphere.marathon.metrics.Metrics

import scala.concurrent.Future
import scala.async.Async.{ async, await }
/**
  * This responsibility is in transit:
  *
  * Current state:
  * - all applications are stored as part of the root group in the group repository for every user intended change
  * - all applications are stored again in the app repository, if the deployment of that application starts
  *
  * Future plan:
  * - the applications should be always loaded via the groupManager or groupRepository.
  * - the app repository is used to store versions of the application
  *
  * Until this plan is implemented, please think carefully when to use the app repository!
  */
class AppEntityRepository(
  val store: EntityStore[AppDefinition],
  val maxVersions: Option[Int] = None,
  val metrics: Metrics)
    extends EntityRepository[AppDefinition] with CoreAppRepository {
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

  /**
    * Returns the app with the supplied id and version.
    */
  def app(appId: PathId, version: Timestamp): Future[Option[AppDefinition]] =
    entity(appId.safePath, version)

  /**
    * Stores the supplied app, now the current version for that apps's id.
    */
  def store(appDef: AppDefinition): Future[Done] =
    storeWithVersion(appDef.id.safePath, appDef.version, appDef).map(_ => Done)

  /**
    * Returns the current version for all apps.
    */
  def apps(): Source[AppDefinition, NotUsed] =
    Source.fromFuture(current()).mapConcat(identity)

  /**
    * Returns a map from PathIds to current app timestamps.
    */
  def currentAppVersions(): Source[AppVersion, NotUsed] =
    apps().map(app => AppVersion(app.id, app.version.toOffsetDateTime))
}
