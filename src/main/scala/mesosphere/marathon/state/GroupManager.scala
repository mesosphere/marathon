package mesosphere.marathon.state

import javax.inject.Inject
import mesosphere.marathon.upgrade.DeploymentPlan
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.google.inject.Singleton
import mesosphere.marathon.MarathonSchedulerService
import org.apache.log4j.Logger
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.api.v2.Group
import scala.util.{Try, Failure, Success}
import com.google.inject.name.Named
import mesosphere.marathon.event.{GroupChangeFailed, GroupChangeSuccess, EventModule}
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
  @Named(EventModule.busName) eventBus: EventStream
) {

  private[this] val log = Logger.getLogger(getClass.getName)

  def list(): Future[Iterable[Group]] = groupRepo.current()

  def group(id: String): Future[Option[Group]] = groupRepo.group(id)

  def group(id:String, version:Timestamp) : Future[Option[Group]] = groupRepo.group(id, version)

  def create(group: Group): Future[Group] = {
    groupRepo.currentVersion(group.id).flatMap {
      case Some(current) =>
        log.warn(s"There is already an group with this id: ${group.id}")
        throw new IllegalArgumentException(s"Can not install group ${group.id}, since there is already a group with this id!")
      case None =>
        log.info(s"Create new Group ${group.id}")
        groupRepo.store(group).flatMap( stored =>
          Future.sequence(stored.apps.map(scheduler.startApp)).map(ignore => stored).andThen(postEvent(group))
        )
    }
  }

  def upgrade(id: String, group: Group): Future[Group] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) => upgrade(current, group)
      case None =>
        log.warn(s"Can not update group $id, since there is no current version!")
        throw new IllegalArgumentException(s"Can not upgrade group $id, since there is no current version!")
    }
  }

  def patch(id: String, fn: Group=>Group): Future[Group] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) => upgrade(current, fn(current))
      case None =>
        log.warn(s"Can not update group $id, since there is no current version!")
        throw new IllegalArgumentException(s"Can not upgrade group $id, since there is no current version!")
    }
  }

  private def upgrade(current: Group, group: Group): Future[Group] = {
    log.info(s"Upgrade existing Group ${group.id} with $group")
    //checkpoint where to start from
    //if there is an upgrade in progress
    val startFromGroup = planRepo.currentVersion(current.id).flatMap {
      case Some(upgrade) =>
        //TODO: signal the scheduler to stop the currently running upgrade
        Future.successful(upgrade.target)
      case None => Future.successful(current)
    }
    val restart = for {
      startGroup  <- startFromGroup
      storedGroup <- groupRepo.store(group)
      plan <- planRepo.store(DeploymentPlan(current.id, startGroup, storedGroup))
      result <- plan.deploy(scheduler) if result
    } yield storedGroup
    //remove the upgrade plan after the task has been finished
    restart.andThen(deletePlan(current.id)).andThen(postEvent(group))
  }

  private def postEvent(group:Group) : PartialFunction[Try[Group], Unit] = {
    case Success(_) => eventBus.publish(GroupChangeSuccess(group.id, group.version.toString))
    case Failure(ex) => eventBus.publish(GroupChangeFailed(group.id, group.version.toString, ex.getMessage))
  }

  private def deletePlan(id:String) : PartialFunction[Try[Group], Unit] = {
    case _ => planRepo.expunge(id)
  }

  def expunge(id: String): Future[Boolean] = {
    groupRepo.currentVersion(id).flatMap {
      case Some(current) => Future.sequence(current.apps.map(scheduler.stopApp)).flatMap(_ => groupRepo.expunge(id).map(_.forall(identity)))
      case None => Future.successful(false)
    }
  }
}
