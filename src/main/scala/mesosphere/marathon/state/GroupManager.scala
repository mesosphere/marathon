package mesosphere.marathon.state

import javax.inject.Inject
import akka.event.EventStream
import com.google.inject.Singleton
import com.google.inject.name.Named
import mesosphere.marathon.MarathonSchedulerService
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event.{EventModule, GroupChangeFailed, GroupChangeSuccess}
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade._
import mesosphere.util.ThreadPoolContext.context
import org.apache.log4j.Logger
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
class GroupManager @Singleton @Inject() (
    scheduler: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupRepo: GroupRepository,
    appRepo: AppRepository,
    @Named(EventModule.busName) eventBus: EventStream) {

  private[this] val log = Logger.getLogger(getClass.getName)
  private[this] val zkName = "root"

  def root: Future[Group] = groupRepo.group(zkName).map(_.getOrElse(Group.empty))

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: PathId): Future[Iterable[Timestamp]] = {
    require(!id.isEmpty, "Empty group id given!")
    groupRepo.listVersions(zkName)
  }

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId): Future[Option[Group]] = {
    root.map(_.findGroup(_.id == id))
  }

  /**
    * Get a specific group with a specific version.
    * @param id the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId, version: Timestamp): Future[Option[Group]] = {
    val versioned = groupRepo.group(zkName, version).map(_.getOrElse(Group.empty))
    versioned.map(_.findGroup(_.id == id))
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
  def update(id: PathId, fn: Group => Group, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[Group] = {
    root.flatMap { current =>
      val update = current.update(id, fn, version)
      upgrade(current, update, force)
    }
  }

  def updateApp(id: PathId, fn: AppDefinition => AppDefinition, version: Timestamp = Timestamp.now(), force: Boolean = false) = {
    root.flatMap{ current =>
      val update = current.updateApp(id, fn, version)
      upgrade(current, update, force)
    }
  }

  private def upgrade(current: Group, group: Group, force: Boolean): Future[Group] = {
    log.info(s"Upgrade existing Group ${group.id} with $group force: $force")
    //TODO(MV): delegate to scheduler - only mock implementation
    def deploy(plan: DeploymentPlan, force: Boolean): Future[Boolean] = {
      plan.steps.flatMap(_.actions).foreach {
        case StartApplication(app, to)     => appRepo.store(app)
        case ScaleApplication(app, to)     => appRepo.store(app)
        case RestartApplication(app, _, _) => appRepo.store(app)
        case StopApplication(app)          => appRepo.expunge(app.id)
        case _                             => //nothing
      }
      log.info(s"Deploy: $plan")
      Future.successful(true)
    }
    val restart = for {
      storedGroup <- groupRepo.store(zkName, group)
      plan = DeploymentPlan(current, storedGroup)
      result <- deploy(plan, force) if result
    } yield storedGroup
    restart.andThen(postEvent(group))
  }

  private def postEvent(group: Group): PartialFunction[Try[Group], Unit] = {
    case Success(_)  => eventBus.publish(GroupChangeSuccess(group.id, group.version.toString))
    case Failure(ex) => eventBus.publish(GroupChangeFailed(group.id, group.version.toString, ex.getMessage))
  }
}
