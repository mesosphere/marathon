package mesosphere.marathon.core.group

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
trait GroupManager {

  def rootGroup(): Future[Group]

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: PathId): Future[Iterable[Timestamp]]

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId): Future[Option[Group]]

  /**
    * Get a specific group with a specific version.
    * @param id the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId, version: Timestamp): Future[Option[Group]]

  /**
    * Get a specific app definition by its id.
    * @param id the id of the app.
    * @return the app uf ut is found, otherwise false
    */
  def app(id: PathId): Future[Option[AppDefinition]]

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
  def update(
    gid: PathId,
    fn: Group => Group,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Map[PathId, Iterable[Task]] = Map.empty): Future[DeploymentPlan]

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
    appId: PathId,
    fn: Option[AppDefinition] => AppDefinition,
    version: Timestamp = Timestamp.now(),
    force: Boolean = false,
    toKill: Iterable[Task] = Iterable.empty): Future[DeploymentPlan]

}
