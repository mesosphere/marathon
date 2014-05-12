package mesosphere.marathon.state

import javax.inject.Inject
import mesosphere.marathon.upgrade.UpgradeManager
import mesosphere.marathon.api.v2.Group
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.inject.Singleton
import mesosphere.marathon.StorageException
import org.apache.log4j.Logger

/**
 * The group manager is the facade for all group related actions.
 * It persists the state of a group and delegates deployments to the UpgradeManager.
 */
class GroupManager @Singleton @Inject() (
  upgradeManager:UpgradeManager,
  repo:GroupRepository
) {

  private[this] val log = Logger.getLogger(getClass.getName)

  def list() : Future[Iterable[Group]] = repo.groups()

  def group(id:String) : Future[Option[Group]] = repo.group(id)

  def create(group:Group) : Future[Group] = {
    repo.currentVersion(group.id).flatMap {
      case Some(current) =>
        log.warn(s"There is already an group with this id: ${group.id}")
        throw new IllegalArgumentException(s"Can not install group ${group.id}, since there is already a group with this id!")
      case None =>
        log.info(s"Create new Group ${group.id}")
        storeWith(group) { storedGroup => upgradeManager.install(storedGroup) }
    }
  }

  def upgrade(id:String, group:Group) : Future[Group] = {
    repo.currentVersion(id).flatMap {
      case Some(current) =>
        log.info(s"Update existing Group $id with $group")
        storeWith(group) { storedGroup => upgradeManager.upgrade(current, storedGroup) }
      case None =>
        log.warn(s"Can not update group $id, since there is no current version!")
        throw new IllegalArgumentException(s"Can not upgrade group $id, since there is no current version!")
    }
  }

  def expunge(id:String) : Future[Boolean] = {
    repo.currentVersion(id).flatMap {
      case Some(current) => upgradeManager.delete(current).flatMap( ignore => repo.expunge(id).map(_.forall(identity)))
      case None => Future.successful(false)
    }
  }

  private[this] def storeWith[T](group:Group)(action:Group=>Future[T]) : Future[T] = {
    repo.store(group).flatMap {
      case Some(storedGroup) => action(storedGroup)
      case None => throw new StorageException(s"Can not store group $group")
    }
  }
}
