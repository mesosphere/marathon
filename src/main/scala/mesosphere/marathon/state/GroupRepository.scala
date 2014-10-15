package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import scala.concurrent.Future

class GroupRepository(
  val store: PersistenceStore[Group],
  appRepo: AppRepository,
  val maxVersions: Option[Int] = None,
  val registry: MetricRegistry)
    extends EntityRepository[Group] {

  import mesosphere.util.ThreadPoolContext.context

  def group(id: String, withLatestApps: Boolean = true): Future[Option[Group]] = {
    if (withLatestApps) fetchWithLatestApp(id) else this.store.fetch(id)
  }

  def group(id: String, version: Timestamp): Future[Option[Group]] = entity(id, version)

  override def currentVersion(id: String): Future[Option[Group]] = fetchWithLatestApp(id)

  //fetch group, while fetching latest app definitions from app repository
  private def fetchWithLatestApp(key: String): Future[Option[Group]] = {
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

  def store(path: String, group: Group): Future[Group] = storeWithVersion(path, group.version, group)
}
