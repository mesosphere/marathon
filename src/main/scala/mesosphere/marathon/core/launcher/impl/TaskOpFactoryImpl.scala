package mesosphere.marathon.core.launcher.impl

import com.google.inject.Inject
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.{ TaskOp, TaskOpFactory }
import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp, Task }
import mesosphere.marathon.core.task.Task.{ LocalVolume, LocalVolumeId, ReservationWithVolumes }
import mesosphere.marathon.state.{ AppDefinition, PersistentVolume }
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.{ PersistentVolumeMatcher, ResourceMatcher, TaskBuilder }
import org.apache.mesos.{ Protos => Mesos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class TaskOpFactoryImpl @Inject() (
  config: MarathonConf,
  clock: Clock)
    extends TaskOpFactory {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val taskOperationFactory = {
    val principalOpt = config.mesosAuthenticationPrincipal.get
    val roleOpt = config.mesosRole.get

    new TaskOpFactoryHelper(principalOpt, roleOpt)
  }

  override def buildTaskOp(app: AppDefinition, offer: Mesos.Offer, tasks: Iterable[Task]): Option[TaskOp] = {
    log.debug("buildTaskOp")

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
        val task = Task.LaunchedEphemeral(
          taskId = Task.Id(taskInfo.getTaskId),
          agentInfo = Task.AgentInfo(
            host = offer.getHostname,
            agentId = Some(offer.getSlaveId.getValue),
            attributes = offer.getAttributesList.asScala
          ),
          appVersion = app.version,
          status = Task.Status(
            stagedAt = clock.now()
          ),
          networking = Task.HostPorts(ports)
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
      if (log.isDebugEnabled) log.debug(s"inferForResidents, acceptedResourceRoles $roles")
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

    def maybeLaunchOnReservation = if (needToLaunch) {
      val maybeVolumeMatch = PersistentVolumeMatcher.matchVolumes(offer, app, waitingTasks)

      maybeVolumeMatch.flatMap { volumeMatch =>
        val matchingReservedResourcesWithoutVolumes =
          ResourceMatcher.matchResources(
            offer, app, tasks,
            ResourceSelector(
              config.mesosRole.get.toSet, reserved = true,
              requiredLabels = TaskLabels.labelsForTask(volumeMatch.task)
            )
          )

        matchingReservedResourcesWithoutVolumes.flatMap { otherResourcesMatch =>
          launchOnReservation(app, offer, volumeMatch.task, matchingReservedResourcesWithoutVolumes, maybeVolumeMatch)
        }
      }
    }
    else None

    def maybeReserveAndCreateVolumes = if (needToReserve) {
      val matchingResourcesForReservation =
        ResourceMatcher.matchResources(offer, app, tasks, ResourceSelector(acceptedResourceRoles, reserved = false))
      matchingResourcesForReservation.map(resourceMatch => reserveAndCreateVolumes(app, offer, resourceMatch))
    }
    else None

    maybeLaunchOnReservation orElse maybeReserveAndCreateVolumes
  }

  private[this] def launchOnReservation(
    app: AppDefinition,
    offer: Mesos.Offer,
    task: Task,
    resourceMatch: Option[ResourceMatcher.ResourceMatch],
    volumeMatch: Option[PersistentVolumeMatcher.VolumeMatch]): Option[TaskOp] = {

    // create a TaskBuilder that used the id of the existing task as id for the created TaskInfo
    new TaskBuilder(app, (_) => task.taskId, config).build(offer, resourceMatch, volumeMatch) map {
      case (taskInfo, ports) =>
        val launch = TaskStateOp.Launch(
          appVersion = app.version,
          status = Task.Status(
            stagedAt = clock.now()
          ),
          networking = Task.HostPorts(ports))

        // FIXME (3221): something like reserved.launch(...): LaunchedOnReservation so we don't need to match?
        task.update(launch) match {
          case TaskStateChange.Update(updatedTask) =>
            taskOperationFactory.launch(taskInfo, updatedTask)

          case unexpected: TaskStateChange =>
            throw new scala.RuntimeException(s"Expected TaskStateChange.Update but got $unexpected")
        }
    }
  }

  private[this] def reserveAndCreateVolumes(
    app: AppDefinition,
    offer: Mesos.Offer,
    resourceMatch: ResourceMatcher.ResourceMatch): TaskOp = {

    val localVolumes: Iterable[LocalVolume] = app.persistentVolumes.map { volume =>
      LocalVolume(LocalVolumeId(app.id, volume), volume)
    }
    val persistentVolumeIds = localVolumes.map(_.id)
    val task = Task.Reserved(
      taskId = Task.Id.forApp(app.id),
      agentInfo = Task.AgentInfo(
        host = offer.getHostname,
        agentId = Some(offer.getSlaveId.getValue),
        attributes = offer.getAttributesList.asScala
      ),
      state = Task.Reserved.State.New(timeout = None),
      reservation = ReservationWithVolumes(persistentVolumeIds)
    )
    taskOperationFactory.reserveAndCreateVolumes(task, resourceMatch.resources, localVolumes)
  }

}
