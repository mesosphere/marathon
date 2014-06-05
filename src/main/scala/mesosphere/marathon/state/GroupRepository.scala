package mesosphere.marathon.state

import mesosphere.marathon.api.v2.Group
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import mesosphere.marathon.StorageException

class GroupRepository(val store: PersistenceStore[Group], appRepo: AppRepository) extends EntityRepository[Group] {

  def group(id: String): Future[Option[Group]] = fetch(id)

  def group(id: String, version: Timestamp): Future[Option[Group]] = entity(id, version)

  override def currentVersion(id: String): Future[Option[Group]] = fetch(id)

  //fetch group, while fetching latest app definitions from app repository
  private def fetch(key: String): Future[Option[Group]] = {
    this.store.fetch(key).flatMap {
      case Some(group) =>
        Future.sequence(group.apps.map(app => appRepo.currentVersion(app.id))).map { apps =>
          Some(group.copy(apps = apps.flatten))
        }
      case None => Future.successful(None: Option[Group])
    }
  }

  def store(group: Group): Future[Group] = storeWithVersion(group.id, group.version, group)
}
