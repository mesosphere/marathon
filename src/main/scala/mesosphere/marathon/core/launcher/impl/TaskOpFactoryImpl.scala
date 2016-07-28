package mesosphere.marathon.core.launcher.impl

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.{ TaskOp, TaskOpFactory }
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ RunSpec => PluginAppDefinition }
import mesosphere.marathon.state.{ ResourceRole, RunSpec }
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.{ PersistentVolumeMatcher, ResourceMatcher, TaskBuilder }
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{ Protos => Mesos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class TaskOpFactoryImpl(
  config: MarathonConf,
  clock: Clock,
  pluginManager: PluginManager = PluginManager.None)
    extends TaskOpFactory {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val taskOperationFactory = {
    val principalOpt = config.mesosAuthenticationPrincipal.get
    val roleOpt = config.mesosRole.get

    new TaskOpFactoryHelper(principalOpt, roleOpt)
  }

  private[this] lazy val appTaskProc: RunSpecTaskProcessor = combine(pluginManager.plugins[RunSpecTaskProcessor])

  override def buildTaskOp(request: TaskOpFactory.Request): Option[TaskOp] = {
    log.debug("buildTaskOp")

    if (request.isForResidentRunSpec) {
      inferForResidents(request)
    } else {
      inferNormalTaskOp(request)
    }
  }

  private[this] def inferNormalTaskOp(request: TaskOpFactory.Request): Option[TaskOp] = {
    val TaskOpFactory.Request(runSpec, offer, tasks, _) = request

    new TaskBuilder(runSpec, Task.Id.forRunSpec, config, Some(appTaskProc)).buildIfMatches(offer, tasks.values).map {
      case (taskInfo, ports) =>
        val task = Task.LaunchedEphemeral(
          taskId = Task.Id(taskInfo.getTaskId),
          agentInfo = Task.AgentInfo(
            host = offer.getHostname,
            agentId = Some(offer.getSlaveId.getValue),
            attributes = offer.getAttributesList.asScala
          ),
          runSpecVersion = runSpec.version,
          status = Task.Status(
            stagedAt = clock.now(),
            taskStatus = MarathonTaskStatus.Created
          ),
          hostPorts = ports.flatten
        )

        taskOperationFactory.launchEphemeral(taskInfo, task)
    }
  }

  private[this] def inferForResidents(request: TaskOpFactory.Request): Option[TaskOp] = {
    val TaskOpFactory.Request(runSpec, offer, tasks, additionalLaunches) = request

    val needToLaunch = additionalLaunches > 0 && request.hasWaitingReservations
    val needToReserve = request.numberOfWaitingReservations < additionalLaunches

    /* *
     * If an offer HAS reservations/volumes that match our run spec, handling these has precedence
     * If an offer NAS NO reservations/volumes that match our run spec, we can reserve if needed
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
      val maybeVolumeMatch = PersistentVolumeMatcher.matchVolumes(offer, runSpec, request.reserved)

      maybeVolumeMatch.flatMap { volumeMatch =>
        // we must not consider the volumeMatch's Reserved task because that would lead to a violation of constraints
        // by the Reserved task that we actually want to launch
        val tasksToConsiderForConstraints = tasks - volumeMatch.task.taskId
        // resources are reserved for this role, so we only consider those resources
        val rolesToConsider = config.mesosRole.get.toSet
        val reservationLabels = TaskLabels.labelsForTask(request.frameworkId, volumeMatch.task).labels
        val matchingReservedResourcesWithoutVolumes =
          ResourceMatcher.matchResources(
            offer, runSpec, tasksToConsiderForConstraints.values,
            ResourceSelector.reservedWithLabels(rolesToConsider, reservationLabels)
          )

        matchingReservedResourcesWithoutVolumes.flatMap { otherResourcesMatch =>
          launchOnReservation(runSpec, offer, volumeMatch.task,
            matchingReservedResourcesWithoutVolumes, maybeVolumeMatch)
        }
      }
    } else None

    def maybeReserveAndCreateVolumes = if (needToReserve) {
      val configuredRoles = runSpec.acceptedResourceRoles.getOrElse(config.defaultAcceptedResourceRolesSet)
      // We can only reserve unreserved resources
      val rolesToConsider = Set(ResourceRole.Unreserved).intersect(configuredRoles)
      if (rolesToConsider.isEmpty) {
        log.warn(s"Will never match for ${runSpec.id}. The runSpec is not configured to accept unreserved resources.")
      }

      val matchingResourcesForReservation =
        ResourceMatcher.matchResources(
          offer, runSpec, tasks.values,
          ResourceSelector.reservable
        )
      matchingResourcesForReservation.map { resourceMatch =>
        reserveAndCreateVolumes(request.frameworkId, runSpec, offer, resourceMatch)
      }
    } else None

    maybeLaunchOnReservation orElse maybeReserveAndCreateVolumes
  }

  private[this] def launchOnReservation(
    spec: RunSpec,
    offer: Mesos.Offer,
    task: Task.Reserved,
    resourceMatch: Option[ResourceMatcher.ResourceMatch],
    volumeMatch: Option[PersistentVolumeMatcher.VolumeMatch]): Option[TaskOp] = {

    // create a TaskBuilder that used the id of the existing task as id for the created TaskInfo
    new TaskBuilder(spec, (_) => task.taskId, config, Some(appTaskProc)).build(offer, resourceMatch, volumeMatch) map {
      case (taskInfo, ports) =>
        val taskStateOp = TaskStateOp.LaunchOnReservation(
          task.taskId,
          runSpecVersion = spec.version,
          status = Task.Status(
            stagedAt = clock.now(),
            taskStatus = MarathonTaskStatus.Created
          ),
          hostPorts = ports.flatten)

        taskOperationFactory.launchOnReservation(taskInfo, taskStateOp, task)
    }
  }

  private[this] def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    RunSpec: RunSpec,
    offer: Mesos.Offer,
    resourceMatch: ResourceMatcher.ResourceMatch): TaskOp = {

    val localVolumes: Iterable[Task.LocalVolume] = RunSpec.persistentVolumes.map { volume =>
      Task.LocalVolume(Task.LocalVolumeId(RunSpec.id, volume), volume)
    }
    val persistentVolumeIds = localVolumes.map(_.id)
    val now = clock.now()
    val timeout = Task.Reservation.Timeout(
      initiated = now,
      deadline = now + config.taskReservationTimeout().millis,
      reason = Task.Reservation.Timeout.Reason.ReservationTimeout
    )
    val task = Task.Reserved(
      taskId = Task.Id.forRunSpec(RunSpec.id),
      agentInfo = Task.AgentInfo(
        host = offer.getHostname,
        agentId = Some(offer.getSlaveId.getValue),
        attributes = offer.getAttributesList.asScala
      ),
      reservation = Task.Reservation(persistentVolumeIds, Task.Reservation.State.New(timeout = Some(timeout))),
      status = Task.Status(
        stagedAt = now,
        taskStatus = MarathonTaskStatus.Reserved
      )
    )
    val taskStateOp = TaskStateOp.Reserve(task)
    taskOperationFactory.reserveAndCreateVolumes(frameworkId, taskStateOp, resourceMatch.resources, localVolumes)
  }

  def combine(procs: Seq[RunSpecTaskProcessor]): RunSpecTaskProcessor =
    RunSpecTaskProcessor{ (app: PluginAppDefinition, b: Mesos.TaskInfo.Builder) => procs.foreach(_(app, b)) }
}
