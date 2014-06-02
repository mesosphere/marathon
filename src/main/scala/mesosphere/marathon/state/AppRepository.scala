package mesosphere.marathon.state

import mesosphere.marathon.api.v1.AppDefinition

import mesosphere.util.ThreadPoolContext.context
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
class AppRepository(val store: PersistenceStore[AppDefinition]) extends EntityRepository[AppDefinition] {

  /**
    * Returns the app with the supplied id and version.
    */
  def app(appId: String, version: Timestamp): Future[Option[AppDefinition]] = entity(appId, version)

  /**
    * Stores the supplied app, now the current version for that apps's id.
    */
  def store(appDef: AppDefinition): Future[AppDefinition] = storeWithVersion(appDef.id, appDef.version, appDef)

  /**
    * Returns the current version for all apps.
    */
  def apps(): Future[Iterable[AppDefinition]] = current()
}
