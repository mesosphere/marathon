package mesosphere.marathon
package core.group

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  *
  * Only 1 update to the root will be processed at a time.
  */
trait GroupManager extends GroupManager.EnforceRoleSettingProvider with GroupManager.RunSpecProvider {

  /**
    * Get a root group, fetching it from a persistence store if necessary.
    *
    * @return a root group
    */
  def rootGroup(): RootGroup

  /**
    * Get a root group.
    *
    * @return a root group if it has been fetched already, otherwise [[None]]
    */
  def rootGroupOption(): Option[RootGroup]

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: AbsolutePathId): Source[Timestamp, NotUsed]

  /**
    * Get all available app versions for a given app id
    */
  def appVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed]

  /**
    * Get the app definition for an id at a specific version
    */
  def appVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Option[AppDefinition]]

  /**
    * Get all available pod versions for a given pod id
    */
  def podVersions(id: AbsolutePathId): Source[OffsetDateTime, NotUsed]

  /**
    * Get the pod definition for an id at a specific version
    */
  def podVersion(id: AbsolutePathId, version: OffsetDateTime): Future[Option[PodDefinition]]

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: AbsolutePathId): Option[Group]

  /**
    * Get a specific group with a specific version.
    * @param id the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: AbsolutePathId, version: Timestamp): Future[Option[Group]]

  /**
    * Get a specific run spec by its Id
    * @param id The id of the runSpec
    * @return The run spec if it is found, otherwise None.
    */
  def runSpec(id: AbsolutePathId): Option[RunSpec]

  /**
    * Get a specific app definition by its id.
    * @param id the id of the app.
    * @return the app if it is found, otherwise None
    */
  def app(id: AbsolutePathId): Option[AppDefinition]

  /**
    * Get a specific app definition by its id.
    * @param ids the ids of the apps.
    * @return the app if it is found, otherwise false
    */
  def apps(ids: Set[AbsolutePathId]): Map[AbsolutePathId, Option[AppDefinition]]

  /**
    * Get a specific pod definition by its id.
    * @param id the id of the pod.
    * @return the pod if it is found, otherwise None
    */
  def pod(id: AbsolutePathId): Option[PodDefinition]

  /**
    * Update a group with given identifier.
    * The change of the group is defined by a change function.
    * The complete tree gets the given version.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * Only a single updateRoot call will be processed at a time and the root will be updated once the plan
    * starts getting processed.
    *
    * @param id The id of the group being edited (for event publishing purposes). This should be the
    *           finest grained group being edited, for example, if app "/a/b/c/d" is being edited,
    *           the id should be "/a/b/c"
    * @param fn the update function, which is applied to the root group
    * @param version the new version of the group, after the change has applied.
    * @param force only one update can be applied to applications at a time. with this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return the deployment plan which will be executed.
    */
  final def updateRoot(
      id: AbsolutePathId,
      fn: RootGroup => RootGroup,
      version: Timestamp = Group.defaultVersion,
      force: Boolean = false,
      toKill: Map[AbsolutePathId, Seq[Instance]] = Map.empty
  ): Future[DeploymentPlan] = updateRootAsync(id, (g: RootGroup) => Future.successful(fn(g)), version, force, toKill)

  final def updateRootAsync(
      id: AbsolutePathId,
      fn: RootGroup => Future[RootGroup],
      version: Timestamp = Group.defaultVersion,
      force: Boolean = false,
      toKill: Map[AbsolutePathId, Seq[Instance]] = Map.empty
  ): Future[DeploymentPlan] = {
    updateRootEither[Nothing](id, fn(_).map(Right(_))(ExecutionContexts.callerThread), version, force, toKill)
      .map(_.right.getOrElse(throw new RuntimeException("Either must be Right here")))(ExecutionContexts.callerThread)
  }

  def updateRootEither[T](
      id: AbsolutePathId,
      fn: RootGroup => Future[Either[T, RootGroup]],
      version: Timestamp = Timestamp.now(),
      force: Boolean = false,
      toKill: Map[AbsolutePathId, Seq[Instance]] = Map.empty
  ): Future[Either[T, DeploymentPlan]]

  /**
    * Updates with root group without triggering a deployment.
    *
    * @param fn The update function applied to the root group.
    * @return done
    */
  def patchRoot(fn: RootGroup => RootGroup): Future[Done]

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
  def updateApp(
      appId: AbsolutePathId,
      fn: Option[AppDefinition] => AppDefinition,
      version: Timestamp = Group.defaultVersion,
      force: Boolean = false,
      toKill: Seq[Instance] = Seq.empty
  ): Future[DeploymentPlan] =
    updateRoot(appId.parent, _.updateApp(appId, fn, version), version, force, Map(appId -> toKill))

  /**
    * Update pod with given identifier and update function.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param podId the identifier of the pod
    * @param fn the pod change function
    * @param version the version of the change
    * @param force if the change has to be forced.
    * @return the deployment plan which will be executed.
    */
  def updatePod(
      podId: AbsolutePathId,
      fn: Option[PodDefinition] => PodDefinition,
      version: Timestamp = Group.defaultVersion,
      force: Boolean = false,
      toKill: Seq[Instance] = Seq.empty
  ): Future[DeploymentPlan] = updateRoot(podId.parent, _.updatePod(podId, fn, version), version, force, Map(podId -> toKill))

  /**
    * Refresh the internal root group cache. When calling this function, the internal hold cached root group will be dropped
    * and reloaded directly
    *
    * @return Done if refresh was successful
    */
  def invalidateAndRefreshGroupCache(): Future[Done]

  /**
    * Invalidate the internal root group cache. When calling this function, the internal hold cached root group will be dropped
    * and only be reloaded if accessed the next time
    *
    * @return Done if invalidation was successful
    */
  def invalidateGroupCache(): Future[Done]

  override def enforceRoleSetting(id: AbsolutePathId): Boolean = {
    rootGroup().group(id).map(_.enforceRole).getOrElse(false)
  }
}

object GroupManager {

  trait RunSpecProvider {

    /**
      * Get a specific run spec by its Id
      * @param id The id of the runSpec
      * @return The run spec if it is found, otherwise None.
      */
    def runSpec(id: AbsolutePathId): Option[RunSpec]
  }

  trait EnforceRoleSettingProvider {

    /**
      * Return the enforceRole setting for the given group, or false if no such group exists
      *
      * @param id The id of a group
      * @return the enforceRole setting of the group with the given id, or false if no such role exists
      */
    def enforceRoleSetting(id: AbsolutePathId): Boolean
  }

}
