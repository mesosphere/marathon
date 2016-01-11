package mesosphere.marathon.state

import mesosphere.marathon.metrics.Metrics
import scala.concurrent.Future

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
class AppRepository(
  val store: EntityStore[AppDefinition],
  val maxVersions: Option[Int] = None,
  val metrics: Metrics)
    extends EntityRepository[AppDefinition] {
  import scala.concurrent.ExecutionContext.Implicits.global

  def allPathIds(): Future[Iterable[PathId]] = allIds().map(_.map(PathId.fromSafePath))

  def currentVersion(appId: PathId): Future[Option[AppDefinition]] = currentVersion(appId.safePath)
  def listVersions(appId: PathId): Future[Iterable[Timestamp]] = listVersions(appId.safePath)
  def expunge(appId: PathId): Future[Iterable[Boolean]] = expunge(appId.safePath)

  /**
    * Returns the app with the supplied id and version.
    */
  def app(appId: PathId, version: Timestamp): Future[Option[AppDefinition]] =
    entity(appId.safePath, version)

  /**
    * Stores the supplied app, now the current version for that apps's id.
    */
  def store(appDef: AppDefinition): Future[AppDefinition] =
    storeWithVersion(appDef.id.safePath, appDef.version, appDef)

  /**
    * Returns the current version for all apps.
    */
  def apps(): Future[Iterable[AppDefinition]] = current()

  /**
    * Returns a map from PathIds to current app timestamps.
    */
  def currentAppVersions(): Future[Map[PathId, Timestamp]] =
    for (as <- apps()) yield as.map { a => a.id -> a.version }.toMap

}
