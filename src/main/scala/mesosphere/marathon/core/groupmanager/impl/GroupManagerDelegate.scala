package mesosphere.marathon.core.groupmanager.impl

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import mesosphere.marathon.core.groupmanager.{ GroupManager, GroupManagerConfig }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future
import scala.concurrent.duration._

private[groupmanager] class GroupManagerDelegate(
    config: GroupManagerConfig,
    actorRef: ActorRef) extends GroupManager {

  override def rootGroup(): Future[Group] = askGroupManagerActor(GroupManagerDelegate.RootGroup).mapTo[Group]

  /**
    * Update application with given identifier and update function.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param appId   the identifier of the application
    * @param fn      the application change function
    * @param version the version of the change
    * @param force   if the change has to be forced.
    * @return the deployment plan which will be executed.
    */
  override def updateApp(
    appId: PathId,
    fn: (Option[AppDefinition]) => AppDefinition,
    version: Timestamp,
    force: Boolean,
    toKill: Iterable[Task]): Future[DeploymentPlan] =
    askGroupManagerActor(
      GroupManagerDelegate.Upgrade(
        appId.parent,
        _.updateApp(appId, fn, version),
        version,
        force,
        Map(appId -> toKill)
      )
    ).mapTo[DeploymentPlan]

  /**
    * Update a group with given identifier.
    * The change of the group is defined by a change function.
    * The complete tree gets the given version.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param gid     the id of the group to change.
    * @param version the new version of the group, after the change has applied.
    * @param fn      the update function, which is applied to the group identified by given id
    * @param force   only one update can be applied to applications at a time. with this flag
    *                one can control, to stop a current deployment and start a new one.
    * @return the deployment plan which will be executed.
    */
  override def update(
    gid: PathId,
    fn: (Group) => Group,
    version: Timestamp,
    force: Boolean,
    toKill: Map[PathId, Iterable[Task]]): Future[DeploymentPlan] =
    askGroupManagerActor(
      GroupManagerDelegate.Upgrade(
        gid,
        _.update(gid, fn, version),
        version,
        force,
        toKill
      )
    ).mapTo[DeploymentPlan]

  /**
    * Get all available versions for given group identifier.
    *
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  override def versions(id: PathId): Future[Iterable[Timestamp]] =
    askGroupManagerActor(GroupManagerDelegate.Versions(id)).mapTo[Iterable[Timestamp]]

  /**
    * Get a specific group by its id.
    *
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  override def group(id: PathId): Future[Option[Group]] =
    askGroupManagerActor(GroupManagerDelegate.GroupWithId(id)).mapTo[Option[Group]]

  /**
    * Get a specific group with a specific version.
    *
    * @param id      the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  override def group(id: PathId, version: Timestamp): Future[Option[Group]] =
    askGroupManagerActor(GroupManagerDelegate.GroupWithVersion(id, version)).mapTo[Option[Group]]

  /**
    * Get a specific app definition by its id.
    *
    * @param id the id of the app.
    * @return the app uf ut is found, otherwise false
    */
  override def app(id: PathId): Future[Option[AppDefinition]] =
    askGroupManagerActor(GroupManagerDelegate.App(id)).mapTo[Option[AppDefinition]]

  private[this] def askGroupManagerActor[T](
    message: T,
    timeout: FiniteDuration = config.groupManagerRequestTimeout().milliseconds): Future[Any] = {
    implicit val timeoutImplicit: Timeout = timeout

    val answerFuture = actorRef ? message
    answerFuture
  }
}

private[impl] object GroupManagerDelegate {

  sealed trait Request

  case class App(id: PathId) extends Request

  case class GroupWithId(id: PathId) extends Request

  case class GroupWithVersion(id: PathId, version: Timestamp) extends Request

  case object RootGroup extends Request

  case class Upgrade(
    gid: PathId,
    change: Group => Group,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Iterable[Task]] = Map.empty) extends Request

  case class Versions(id: PathId) extends Request

}
