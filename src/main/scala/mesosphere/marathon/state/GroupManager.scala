package mesosphere.marathon.state

import javax.inject.Inject
import mesosphere.marathon.upgrade.DeploymentPlan
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.inject.Singleton
import mesosphere.marathon.{ TaskUpgradeCancelledException, UpgradeInProgressException, MarathonSchedulerService }
import org.apache.log4j.Logger
import mesosphere.marathon.tasks.TaskTracker
import scala.util.{ Try, Failure, Success }
import com.google.inject.name.Named
import mesosphere.marathon.event.{ GroupChangeFailed, GroupChangeSuccess, EventModule }
import akka.event.EventStream

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

  def list(): Future[Iterable[Group]] = groupRepo.current()

  def versions(id: GroupId): Future[Iterable[Timestamp]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.listVersions(id.root)
  }

  def group(id: GroupId): Future[Option[Group]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.group(id.root).map(_.flatMap(_.findGroup(_.id == id)))
  }

  def group(id: GroupId, version: Timestamp): Future[Option[Group]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.group(id.root, version).map(_.flatMap(_.findGroup(_.id == id)))
  }

  def create(group: Group): Future[Group] = {
    groupRepo.currentVersion(group.id).flatMap {
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

  def update(id: String, group: Group, force: Boolean): Future[Group] = update(id, _ => group, force)

  def update(id: String, fn: Group => Group, force: Boolean): Future[Group] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) => upgrade(current, fn(current), force)
      case None =>
        log.warn(s"Can not update group $id, since there is no current version!")
        throw new IllegalArgumentException(s"Can not upgrade group $id, since there is no current version!")
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

  def expunge(id: String): Future[Boolean] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) => Future.sequence(current.transitiveApps.map(scheduler.stopApp)).flatMap(_ => groupRepo.expunge(id).map(_.forall(identity)))
      case None          => Future.successful(false)
    }
  }
}
