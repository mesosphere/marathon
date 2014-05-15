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
  def store(appDef: AppDefinition): Future[Option[AppDefinition]] = {
    val key = appDef.id + ID_DELIMITER + appDef.version.toString
    val versionedRes = store.store(appDef.id, appDef)
    val currentRes = store.store(key, appDef)

    for {
      _ <- versionedRes
      current <- currentRes
    } yield current
  }

  /**
    * Returns the current version for all apps.
    */
  def apps(): Future[Iterable[AppDefinition]] = current()
}
