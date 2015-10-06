package mesosphere.marathon.state

import mesosphere.marathon.metrics.Metrics

import scala.concurrent.Future

class GroupRepository(
  val store: EntityStore[Group],
  val maxVersions: Option[Int] = None,
  val metrics: Metrics)
    extends EntityRepository[Group] {

  val zkRootName = GroupRepository.zkRootName

  def group(id: String): Future[Option[Group]] = timedRead { this.store.fetch(id) }

  def rootGroup(): Future[Option[Group]] = timedRead { this.store.fetch(zkRootName) }

  def group(id: String, version: Timestamp): Future[Option[Group]] = entity(id, version)

  def store(path: String, group: Group): Future[Group] = storeWithVersion(path, group.version, group)
}

object GroupRepository {
  val zkRootName = "root"
}
