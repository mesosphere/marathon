package mesosphere.marathon.core.launcher.impl

import com.google.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.{ TaskOp, TaskOpFactory }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ LocalVolume, LocalVolumeId, ReservationWithVolumes }
import mesosphere.marathon.state.{ AppDefinition, PersistentVolume }
import mesosphere.mesos.{ PersistentVolumeMatcher, ResourceMatcher, TaskBuilder }
import org.apache.mesos.{ Protos => Mesos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class DefaultTaskOpFactory @Inject() (
  config: MarathonConf,
  clock: Clock)
    extends TaskOpFactory {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val taskOperationFactory = {
    val principalOpt = config.mesosAuthenticationPrincipal.get
    val roleOpt = config.mesosRole.get

    new TaskOpFactoryHelper(principalOpt, roleOpt)
  }

  override def inferTaskOp(app: AppDefinition, offer: Mesos.Offer, tasks: Iterable[Task]): Option[TaskOp] = {
    log.debug(s"inferTaskOp")

    if (app.isResident) {
      inferForResidents(app, offer, tasks)
    }
    else {
      inferNormalTaskOp(app, offer, tasks)
    }
  }

  private[this] def inferNormalTaskOp(app: AppDefinition, offer: Mesos.Offer, tasks: Iterable[Task]): Option[TaskOp] = {
    new TaskBuilder(app, Task.Id.forApp, config).buildIfMatches(offer, tasks).map {
      case (taskInfo, ports) =>
        val task = Task(
          taskId = Task.Id(taskInfo.getTaskId),
          agentInfo = Task.AgentInfo(
            host = offer.getHostname,
            agentId = Some(offer.getSlaveId.getValue),
            attributes = offer.getAttributesList.asScala
          ),
          launched = Some(
            Task.Launched(
              appVersion = app.version,
              status = Task.Status(
                stagedAt = clock.now()
              ),
              networking = Task.HostPorts(ports)
            )
          )
        )
        taskOperationFactory.launch(taskInfo, task)
    }
  }

  private[this] def inferForResidents(app: AppDefinition, offer: Mesos.Offer, tasks: Iterable[Task]): Option[TaskOp] = {
    val (launchedTasks, waitingTasks) = tasks.partition(_.launched.isDefined)
    val needToLaunch = launchedTasks.size < app.instances && waitingTasks.nonEmpty
    val needToReserve = tasks.size < app.instances

    val acceptedResourceRoles: Set[String] = {
      val roles = app.acceptedResourceRoles.getOrElse(config.defaultAcceptedResourceRolesSet)
      if (log.isDebugEnabled) log.debug(s"acceptedResourceRoles $roles")
      roles
    }

    /* *
     * If an offer HAS reservations/volumes that match our app, handling these has precedence
     * If an offer NAS NO reservations/volumes that match our app, we can reserve if needed
     *
     * Scenario 1:
     *  We need to launch tasks and receive an offer that HAS matching reservations/volumes
     *  - check if we have a task that need those volumes
     *  - if we do: schedule a Launch TaskOp for the task
     *  - if we don't: skip for now
     *
     * Scenario 2:
     *  We ned to reserve resources and receive an offer that has matching resources
     *  - schedule a ReserveAndCreate TaskOp
     */

    lazy val matchingVolumes = PersistentVolumeMatcher.matchVolumes(offer, app, waitingTasks)
    lazy val matchingResources = ResourceMatcher.matchResources(offer, app, tasks, acceptedResourceRoles)

    val taskOp: Option[TaskOp] = if (needToLaunch && matchingVolumes.isDefined && matchingResources.isDefined) {
      launch(app, offer, matchingVolumes.get.task, matchingResources, matchingVolumes)
    }
    else if (needToReserve && matchingResources.isDefined) {
      matchingResources.map(resourceMatch => reserveAndCreateVolumes(app, offer, resourceMatch))
    }
    else {
      None
    }

    taskOp
  }

  private[this] def launch(
    app: AppDefinition,
    offer: Mesos.Offer,
    task: Task,
    resourceMatch: Option[ResourceMatcher.ResourceMatch],
    volumeMatch: Option[PersistentVolumeMatcher.VolumeMatch]): Option[TaskOp] = {

    // create a TaskBuilder that used the id of the existing task as id for the created TaskInfo
    new TaskBuilder(app, (_) => task.taskId, config).build(offer, resourceMatch, volumeMatch) map {
      case (taskInfo, ports) =>
        val newTask = task.copy(
          agentInfo = Task.AgentInfo(
            host = offer.getHostname,
            agentId = Some(offer.getSlaveId.getValue),
            attributes = offer.getAttributesList.asScala
          ),
          launched = Some(
            Task.Launched(
              appVersion = app.version,
              status = Task.Status(
                stagedAt = clock.now()
              ),
              networking = Task.HostPorts(ports)
            )
          )
        )
        taskOperationFactory.launch(taskInfo, newTask)
    }
  }

  private[this] def reserveAndCreateVolumes(
    app: AppDefinition,
    offer: Mesos.Offer,
    resourceMatch: ResourceMatcher.ResourceMatch): TaskOp = {

    val persistentVolumes = app.container.fold(Seq.empty[PersistentVolume])(_.volumes.collect{
      case volume @ PersistentVolume(_, _, _) => volume
    })
    val resources = new TaskBuilder(app, Task.Id.forApp, config).getResources(resourceMatch, persistentVolumes)
    val localVolumes: Iterable[LocalVolume] = persistentVolumes.map { volume =>
      LocalVolume(LocalVolumeId(app.id, volume), volume)
    }
    val persistentVolumeIds = localVolumes.map(_.id)
    val task = Task(
      taskId = Task.Id.forApp(app.id),
      agentInfo = Task.AgentInfo(
        host = offer.getHostname,
        agentId = Some(offer.getSlaveId.getValue),
        attributes = offer.getAttributesList.asScala
      ),
      launched = None,
      reservationWithVolumes = Some(ReservationWithVolumes(persistentVolumeIds))
    )
    taskOperationFactory.reserveAndCreateVolumes(task, resources, localVolumes)
  }

}
