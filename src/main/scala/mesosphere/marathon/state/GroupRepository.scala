package mesosphere.marathon.state

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class GroupRepository(val store: PersistenceStore[Group], appRepo: AppRepository) extends EntityRepository[Group] {

  def group(id: String): Future[Option[Group]] = fetch(id)

  def group(id: String, version: Timestamp): Future[Option[Group]] = entity(id, version)

  override def currentVersion(id: String): Future[Option[Group]] = fetch(id)

  //fetch group, while fetching latest app definitions from app repository
  private def fetch(key: String): Future[Option[Group]] = {
    this.store.fetch(key).flatMap {
      case Some(group) =>
        Future.sequence(group.transitiveApps.map(app => appRepo.currentVersion(app.id))).map { apps =>
          val allApps = apps.flatten.map(app => app.id -> app).toMap
          Some(group.update(group.version) { g =>
            if (g.apps.isEmpty) g else g.copy(apps = g.apps.map(_.id).flatMap(allApps.get))
          })
        }
      case None => Future.successful(None: Option[Group])
    }
  }

  def store(group: Group): Future[Group] = storeWithVersion(group.id.safePath, group.version, group)
}
