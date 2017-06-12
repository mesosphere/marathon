package mesosphere.marathon
package core.group.impl

import java.time.OffsetDateTime

import akka.{ Done, NotUsed }
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import mesosphere.marathon.core.group.impl.GroupManagerActor.InvalidateGroupCache
import mesosphere.marathon.core.group.{ GroupManager, GroupManagerConfig }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, RootGroup, RunSpec, Timestamp }
import mesosphere.marathon.storage.repository.{ ReadOnlyAppRepository, ReadOnlyPodRepository }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

private[group] class GroupManagerDelegate(
    config: GroupManagerConfig,
    appRepository: ReadOnlyAppRepository,
    podRepository: ReadOnlyPodRepository,
    actorRef: ActorRef) extends GroupManager {

  override def rootGroup(): Future[RootGroup] = askGroupManagerActor(GroupManagerActor.GetRootGroup).mapTo[RootGroup]

  /**
    * Update the root group.
    * The change of the root group is defined by a change function.
    * The complete tree gets the given version.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param fn      the update function, which is applied to the root group
    * @param version the new version of the group, after the change has applied.
    * @param force   only one update can be applied to applications at a time. with this flag
    *                one can control, to stop a current deployment and start a new one.
    * @return the deployment plan which will be executed.
    */
  override def updateRoot(
    fn: RootGroup => RootGroup,
    version: Timestamp,
    force: Boolean,
    toKill: Map[PathId, Seq[Instance]]): Future[DeploymentPlan] =
    askGroupManagerActor(
      GroupManagerActor.GetUpgrade(
        PathId.empty,
        fn,
        version,
        force,
        toKill
      )
    ).mapTo[DeploymentPlan]

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
    toKill: Seq[Instance]): Future[DeploymentPlan] =
    askGroupManagerActor(
      GroupManagerActor.GetUpgrade(
        appId.parent,
        _.updateApp(appId, fn, version),
        version,
        force,
        Map(appId -> toKill)
      )
    ).mapTo[DeploymentPlan]

  override def updatePod(
    podId: PathId,
    fn: (Option[PodDefinition]) => PodDefinition,
    version: Timestamp,
    force: Boolean,
    toKill: Seq[Instance]): Future[DeploymentPlan] =
    askGroupManagerActor(
      GroupManagerActor.GetUpgrade(
        podId.parent,
        _.updatePod(podId, fn, version),
        version,
        force,
        Map(podId -> toKill)
      )
    ).mapTo[DeploymentPlan]

  /**
    * Get all available versions for given group identifier.
    *
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  override def versions(id: PathId): Future[Seq[Timestamp]] =
    askGroupManagerActor(GroupManagerActor.GetAllVersions(id)).mapTo[Seq[Timestamp]]

  /**
    * Get all available app versions for a given app id
    */
  override def appVersions(id: PathId): Source[OffsetDateTime, NotUsed] = appRepository.versions(id)

  /**
    * Get the app definition for an id at a specific version
    */
  override def appVersion(id: PathId, version: OffsetDateTime): Future[Option[AppDefinition]] =
    appRepository.getVersion(id, version)

  /**
    * Get the pod definition for an id at a specific version
    */
  override def podVersion(id: PathId, version: OffsetDateTime): Future[Option[PodDefinition]] =
    podRepository.getVersion(id, version)

  /**
    * Get all available pod versions for a given pod id
    */
  override def podVersions(id: PathId): Source[OffsetDateTime, NotUsed] = podRepository.versions(id)

  /**
    * Get a specific group by its id.
    *
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  override def group(id: PathId): Future[Option[Group]] =
    askGroupManagerActor(GroupManagerActor.GetGroupWithId(id)).mapTo[Option[Group]]

  /**
    * Get a specific group with a specific version.
    *
    * @param id      the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  override def group(id: PathId, version: Timestamp): Future[Option[Group]] =
    askGroupManagerActor(GroupManagerActor.GetGroupWithVersion(id, version)).mapTo[Option[Group]]

  /**
    * Get a specific run spec by its Id
    *
    * @param id The id of the runSpec
    * @return The run spec if it is found, otherwise none.
    */
  override def runSpec(id: PathId): Future[Option[RunSpec]] =
    askGroupManagerActor(GroupManagerActor.GetRunSpecWithId(id)).mapTo[Option[RunSpec]]

  /**
    * Get a specific app definition by its id.
    *
    * @param id the id of the app.
    * @return the app uf ut is found, otherwise false
    */
  override def app(id: PathId): Future[Option[AppDefinition]] =
    askGroupManagerActor(GroupManagerActor.GetAppWithId(id)).mapTo[Option[AppDefinition]]

  /**
    * Get a specific pod definition by its id.
    *
    * @param id the id of the pod.
    * @return the pod if it is found, otherwise false
    */
  override def pod(id: PathId): Future[Option[PodDefinition]] =
    askGroupManagerActor(GroupManagerActor.GetPodWithId(id)).mapTo[Option[PodDefinition]]

  private[this] def askGroupManagerActor[T](
    message: T,
    timeout: FiniteDuration = config.groupManagerRequestTimeout().milliseconds): Future[Any] = {
    implicit val timeoutImplicit: Timeout = timeout

    val answerFuture = actorRef ? message
    answerFuture
  }

  override def refreshGroupCache(): Future[Done] = {
    askGroupManagerActor(InvalidateGroupCache).mapTo[Done]
  }
}
