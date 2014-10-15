package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.util.ThreadPoolContext.context
import scala.concurrent.Future

class AppRepository(
  val store: PersistenceStore[AppDefinition],
  val maxVersions: Option[Int] = None,
  val registry: MetricRegistry)
    extends EntityRepository[AppDefinition] with StateMetrics {

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
