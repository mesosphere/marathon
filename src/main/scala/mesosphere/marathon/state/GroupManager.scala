package mesosphere.marathon.state

import javax.inject.Inject
import mesosphere.marathon.upgrade.DeploymentPlan
import scala.concurrent.Future
import com.google.inject.Singleton
import mesosphere.marathon.{ TaskUpgradeCancelledException, UpgradeInProgressException, MarathonSchedulerService }
import org.apache.log4j.Logger
import mesosphere.marathon.tasks.TaskTracker
import scala.util.{ Try, Failure, Success }
import com.google.inject.name.Named
import mesosphere.marathon.event.{ GroupChangeFailed, GroupChangeSuccess, EventModule }
import akka.event.EventStream
import mesosphere.util.ThreadPoolContext.context

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
class GroupManager @Singleton @Inject() (
    scheduler: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupRepo: GroupRepository,
    planRepo: DeploymentPlanRepository,
    @Named(EventModule.busName) eventBus: EventStream) {

  private[this] val log = Logger.getLogger(getClass.getName)

  /**
    * List all current versions of all top level groups.
    */
  def list(): Future[Iterable[Group]] = groupRepo.current()

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: GroupId): Future[Iterable[Timestamp]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.listVersions(id.root)
  }

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: GroupId): Future[Option[Group]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.group(id.root).map(_.flatMap(_.findGroup(_.id == id)))
  }

  /**
    * Get a specific group with a specific version.
    * @param id the idenfifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: GroupId, version: Timestamp): Future[Option[Group]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.group(id.root, version).map(_.flatMap(_.findGroup(_.id == id)))
  }

  /**
    * Create a new group.
    * @param group the group to create.
    * @return the stored group future.
    */
  def create(group: Group): Future[Group] = {
    groupRepo.currentVersion(group.id.root).flatMap {
      case Some(current) =>
        log.warn(s"There is already an group with this id: ${group.id}")
        throw new IllegalArgumentException(s"Can not install group ${group.id}, since there is already a group with this id!")
      case None =>
        log.info(s"Create new Group ${group.id}")
        groupRepo.store(group).flatMap(stored =>
          Future.sequence(stored.transitiveApps.map(scheduler.startApp)).map(ignore => stored).andThen(postEvent(group))
        )
    }
  }

  /**
    * Update a group with given identifier.
    * The change of the group is defined by a change function.
    * The complete tree gets the given version.
    * @param id the id of the group to change.
    * @param version the new version of the group, after the change has applied.
    * @param fn the update function, which is applied to the group identified by given id
    * @param force only one update can be applied to applications at a time. with this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return the nw group future, which completes, when the update process has been finished.
    */
  def update(id: GroupId, version: Timestamp, fn: Group => Group, force: Boolean): Future[Group] = {
    groupRepo.currentVersion(id.root).map(_.getOrElse(Group.emptyWithId(id.root))).flatMap { current =>
      val update = current.makeGroup(id).update(version) {
        group => if (group.id == id) fn(group) else group
      }
      upgrade(current, update, force)
    }
  }

  private def upgrade(current: Group, group: Group, force: Boolean): Future[Group] = {
    log.info(s"Upgrade existing Group ${group.id} with $group force: $force")
    //checkpoint where to start from
    //if there is an upgrade in progress
    val startFromGroup = planRepo.currentVersion(current.id).map {
      case Some(upgrade) =>
        if (!force) throw UpgradeInProgressException(s"Running upgrade for group ${current.id}. Use force flag to override!")
        upgrade.target
      case None => current
    }
    val restart = for {
      startGroup <- startFromGroup
      storedGroup <- groupRepo.store(group)
      plan <- planRepo.store(DeploymentPlan(current.id, startGroup, storedGroup))
      result <- plan.deploy(scheduler, force) if result
    } yield storedGroup
    //remove the upgrade plan after the task has been finished
    restart.andThen(deletePlan(current.id)).andThen(postEvent(group))
  }

  private def postEvent(group: Group): PartialFunction[Try[Group], Unit] = {
    case Success(_)  => eventBus.publish(GroupChangeSuccess(group.id, group.version.toString))
    case Failure(ex) => eventBus.publish(GroupChangeFailed(group.id, group.version.toString, ex.getMessage))
  }

  private def deletePlan(id: String): PartialFunction[Try[Group], Unit] = {
    case Failure(ex: TaskUpgradeCancelledException) => //do not delete the plan, if a rollback is requested
    case Failure(ex: UpgradeInProgressException) => //do not delete the plan, if there is an upgrade in progress
    case _ => planRepo.expunge(id)
  }

  def expunge(id: GroupId): Future[Boolean] = {
    log.info(s"Delete group $id")
    groupRepo.currentVersion(id.root).flatMap {
      case Some(current) => Future.sequence(current.transitiveApps.map(scheduler.stopApp)).flatMap(_ => groupRepo.expunge(id).map(_.forall(identity)))
      case None          => Future.successful(false)
    }
  }
}
