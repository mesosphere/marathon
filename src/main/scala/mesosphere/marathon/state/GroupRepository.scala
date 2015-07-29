package mesosphere.marathon.state

import mesosphere.marathon.metrics.Metrics

import scala.concurrent.Future

class GroupRepository(
  val store: EntityStore[Group],
  appRepo: AppRepository,
  val maxVersions: Option[Int] = None,
  val metrics: Metrics)
    extends EntityRepository[Group] {

  val zkRootName = "root"

  def group(id: String): Future[Option[Group]] = this.store.fetch(id)

  def rootGroup(): Future[Option[Group]] = this.store.fetch(zkRootName)

  def group(id: String, version: Timestamp): Future[Option[Group]] = entity(id, version)

  def store(path: String, group: Group): Future[Group] = storeWithVersion(path, group.version, group)
}
