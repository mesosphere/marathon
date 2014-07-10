package mesosphere.marathon.state

import javax.inject.Inject

import akka.event.EventStream
import com.google.inject.Singleton
import com.google.inject.name.Named
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.ModelValidation
import mesosphere.marathon.event.{ EventModule, GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade._
import mesosphere.util.ThreadPoolContext.context
import org.apache.log4j.Logger

import scala.concurrent.Future
import scala.util.{ Failure, Success }

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
class GroupManager @Singleton @Inject() (
    scheduler: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    @Named(EventModule.busName) eventBus: EventStream) extends ModelValidation {

  private[this] val log = Logger.getLogger(getClass.getName)
  private[this] val zkName = "root"

  def root(withLatestApps: Boolean = true): Future[Group] = groupRepo.group(zkName, withLatestApps).map(_.getOrElse(Group.empty))

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: PathId): Future[Iterable[Timestamp]] = {
    groupRepo.listVersions(zkName).flatMap { versions =>
      Future.sequence(versions.map(groupRepo.group(zkName, _))).map {
        _.collect {
          case Some(group) if group.group(id).isDefined => group.version
        }
      }
    }
  }

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId): Future[Option[Group]] = {
    root().map(_.findGroup(_.id == id))
  }

  /**
    * Get a specific group with a specific version.
    * @param id the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId, version: Timestamp): Future[Option[Group]] = {
    groupRepo.group(zkName, version).map {
      _.flatMap(_.findGroup(_.id == id))
    }
  }

  /**
    * Update a group with given identifier.
    * The change of the group is defined by a change function.
    * The complete tree gets the given version.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param gid the id of the group to change.
    * @param version the new version of the group, after the change has applied.
    * @param fn the update function, which is applied to the group identified by given id
    * @param force only one update can be applied to applications at a time. with this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return the deployment plan which will be executed.
    */
  def update(gid: PathId, fn: Group => Group, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[DeploymentPlan] = {
    upgrade(gid, _.update(gid, fn, version), version, force)
  }

  /**
    * Update application with given identifier and update function.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param appId the identifier of the application
    * @param fn the application change function
    * @param version the version of the change
    * @param force if the change has to be forced.
    * @return the deployment plan which will be executed.
    */
  def updateApp(appId: PathId, fn: AppDefinition => AppDefinition, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[DeploymentPlan] = {
    upgrade(appId.parent, _.updateApp(appId, fn, version), version, force)
  }

  private def upgrade(gid: PathId, fn: Group => Group, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[DeploymentPlan] = {
    log.info(s"Upgrade id:$gid version:$version with force:$force")

    def deploy(from: Group): Future[DeploymentPlan] = {
      val to = fn(from)
      requireValid(checkGroup(to))
      val plan = DeploymentPlan(from, to, version)
      scheduler.deploy(plan, force).map(_ => plan)
    }

    val deployment = for {
      current <- root(withLatestApps = false) //ignore the state of the scheduler
      plan <- deploy(current)
      storedGroup <- groupRepo.store(zkName, plan.target)
    } yield plan

    deployment.onComplete {
      case Success(plan) =>
        log.info(s"Deployment finished for change: $plan")
        eventBus.publish(GroupChangeSuccess(gid, version.toString))
      case Failure(ex) =>
        log.warn(s"Deployment failed for change: $version")
        eventBus.publish(GroupChangeFailed(gid, version.toString, ex.getMessage))
    }
    deployment
  }
}
