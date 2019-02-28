package mesosphere.marathon
package core.launcher.impl

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.{ AgentInfo, InstanceState }
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance }
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.plugin.scheduler.SchedulerPlugin
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ ApplicationSpec, PodSpec }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.{ NoOfferMatchReason, PersistentVolumeMatcher, ResourceMatchResponse, ResourceMatcher, RunSpecOfferMatcher, TaskBuilder, TaskGroupBuilder }
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ ExecutorInfo, TaskGroupInfo, TaskInfo }
import org.apache.mesos.{ Protos => Mesos }

import scala.concurrent.duration._

class InstanceOpFactoryImpl(
  config: MarathonConf,
  pluginManager: PluginManager = PluginManager.None)(implicit clock: Clock)
    extends InstanceOpFactory with StrictLogging {

  import InstanceOpFactoryImpl._

  private[this] val taskOperationFactory = {
    val principalOpt = config.mesosAuthenticationPrincipal.get
    val roleOpt = config.mesosRole.get

    new InstanceOpFactoryHelper(principalOpt, roleOpt)
  }

  private[this] val schedulerPlugins: Seq[SchedulerPlugin] = pluginManager.plugins[SchedulerPlugin]

  private[this] lazy val runSpecTaskProc: RunSpecTaskProcessor = combine(
    pluginManager.plugins[RunSpecTaskProcessor].toIndexedSeq)

  override def matchOfferRequest(request: InstanceOpFactory.Request): OfferMatchResult = {
    logger.debug("matchOfferRequest")

    request.runSpec match {
      case app: AppDefinition =>
        if (request.isForResidentRunSpec) {
          inferForResidents(app, request)
        } else {
          inferNormalTaskOp(app, request)
        }
      case pod: PodDefinition =>
        inferPodInstanceOp(request, pod)
      case _ =>
        throw new IllegalArgumentException(s"unsupported runSpec object ${request.runSpec}")
    }
  }

  protected def inferPodInstanceOp(request: InstanceOpFactory.Request, pod: PodDefinition): OfferMatchResult = {
    val builderConfig = TaskGroupBuilder.BuilderConfig(
      config.defaultAcceptedResourceRolesSet,
      config.envVarsPrefix.get,
      config.mesosBridgeName())

    val matchedOffer =
      RunSpecOfferMatcher.matchOffer(pod, request.offer, request.instances, builderConfig.acceptedResourceRoles, config, schedulerPlugins)

    matchedOffer match {
      case matches: ResourceMatchResponse.Match =>
        val (executorInfo, groupInfo, hostPorts, instanceId) = TaskGroupBuilder.build(pod, request.offer,
          Instance.Id.forRunSpec, builderConfig, runSpecTaskProc, matches.resourceMatch)

        // TODO(jdef) no support for resident tasks inside pods for the MVP
        val agentInfo = Instance.AgentInfo(request.offer)
        val taskIDs: Seq[Task.Id] = groupInfo.getTasksList.map { t => Task.Id(t.getTaskId) }(collection.breakOut)
        val instance = ephemeralPodInstance(pod, agentInfo, taskIDs, hostPorts, instanceId)
        val instanceOp = taskOperationFactory.launchEphemeral(executorInfo, groupInfo, Instance.LaunchRequest(instance))
        OfferMatchResult.Match(pod, request.offer, instanceOp, clock.now())
      case matchesNot: ResourceMatchResponse.NoMatch =>
        OfferMatchResult.NoMatch(pod, request.offer, matchesNot.reasons, clock.now())
    }
  }

  private[this] def inferNormalTaskOp(app: AppDefinition, request: InstanceOpFactory.Request): OfferMatchResult = {
    val InstanceOpFactory.Request(runSpec, offer, instances, _) = request

    val matchResponse =
      RunSpecOfferMatcher.matchOffer(app, offer, instances.values.toIndexedSeq, config.defaultAcceptedResourceRolesSet, config, schedulerPlugins)
    matchResponse match {
      case matches: ResourceMatchResponse.Match =>
        val taskId = Task.Id.forRunSpec(app.id)
        val taskBuilder = new TaskBuilder(app, taskId, config, runSpecTaskProc)
        val (taskInfo, networkInfo) = taskBuilder.build(request.offer, matches.resourceMatch, None)
        val task = Task.LaunchedEphemeral(
          taskId = Task.Id(taskInfo.getTaskId),
          runSpecVersion = runSpec.version,
          status = Task.Status(
            stagedAt = clock.now(),
            condition = Condition.Created,
            networkInfo = networkInfo
          )
        )

        val agentInfo = AgentInfo(offer)
        val instance = LegacyAppInstance(task, agentInfo, app.unreachableStrategy)
        val instanceOp = taskOperationFactory.launchEphemeral(taskInfo, task, instance)
        OfferMatchResult.Match(app, request.offer, instanceOp, clock.now())
      case matchesNot: ResourceMatchResponse.NoMatch => OfferMatchResult.NoMatch(app, request.offer, matchesNot.reasons, clock.now())
    }
  }

  private[this] def inferForResidents(app: AppDefinition, request: InstanceOpFactory.Request): OfferMatchResult = {
    val InstanceOpFactory.Request(runSpec, offer, instances, additionalLaunches) = request

    // TODO(jdef) pods should be supported some day

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
     *  We need to reserve resources and receive an offer that has matching resources
     *  - schedule a ReserveAndCreate TaskOp
     */

    def maybeLaunchOnReservation: Option[OfferMatchResult] = if (needToLaunch) {
      val maybeVolumeMatch = PersistentVolumeMatcher.matchVolumes(offer, request.reserved)

      maybeVolumeMatch.map { volumeMatch =>

        // The volumeMatch identified a specific instance that matches the volume's reservation labels.
        // This is the instance we want to launch. However, when validating constraints, we need to exclude that one
        // instance: it would be considered as an instance on that agent, and would violate e.g. a hostname:unique
        // constraint although it is just a placeholder for the instance that will be launched.
        val instancesToConsiderForConstraints: Stream[Instance] =
          instances.valuesIterator.toStream.filterAs(_.instanceId != volumeMatch.instance.instanceId)

        // resources are reserved for this role, so we only consider those resources
        val rolesToConsider = config.mesosRole.get.toSet
        val reservationLabels = TaskLabels.labelsForTask(request.frameworkId, volumeMatch.instance.appTask.taskId).labels
        val resourceMatchResponse =
          ResourceMatcher.matchResources(
            offer, runSpec, instancesToConsiderForConstraints,
            ResourceSelector.reservedWithLabels(rolesToConsider, reservationLabels),
            config,
            schedulerPlugins,
            request.reserved
          )

        resourceMatchResponse match {
          case matches: ResourceMatchResponse.Match =>
            val instanceOp = launchOnReservation(app, offer, volumeMatch.instance, matches.resourceMatch, volumeMatch)
            OfferMatchResult.Match(app, request.offer, instanceOp, clock.now())
          case matchesNot: ResourceMatchResponse.NoMatch =>
            OfferMatchResult.NoMatch(app, request.offer, matchesNot.reasons, clock.now())
        }
      }
    } else None

    def maybeReserveAndCreateVolumes: Option[OfferMatchResult] = if (needToReserve) {
      val configuredRoles = if (runSpec.acceptedResourceRoles.isEmpty) {
        config.defaultAcceptedResourceRolesSet
      } else {
        runSpec.acceptedResourceRoles
      }
      // We can only reserve unreserved resources
      val rolesToConsider = Set(ResourceRole.Unreserved).intersect(configuredRoles)
      if (rolesToConsider.isEmpty) {
        logger.warn(s"Will never match for ${runSpec.id}. The runSpec is not configured to accept unreserved resources.")
      }

      val resourceMatchResponse =
        ResourceMatcher.matchResources(offer, runSpec, instances.valuesIterator.toStream, ResourceSelector.reservable,
          config,
          schedulerPlugins)
      resourceMatchResponse match {
        case matches: ResourceMatchResponse.Match =>
          val instanceOp = reserveAndCreateVolumes(request.frameworkId, runSpec, offer, matches.resourceMatch)
          Some(OfferMatchResult.Match(app, request.offer, instanceOp, clock.now()))
        case matchesNot: ResourceMatchResponse.NoMatch =>
          Some(OfferMatchResult.NoMatch(app, request.offer, matchesNot.reasons, clock.now()))
      }
    } else None

    maybeLaunchOnReservation
      .orElse(maybeReserveAndCreateVolumes)
      .getOrElse {
        logger.warn("No need to reserve or launch and offer request isForResidentRunSpec")
        OfferMatchResult.NoMatch(app, request.offer,
          Seq(NoOfferMatchReason.NoCorrespondingReservationFound), clock.now())
      }
  }

  private[this] def launchOnReservation(
    spec: AppDefinition,
    offer: Mesos.Offer,
    reservedInstance: Instance,
    resourceMatch: ResourceMatcher.ResourceMatch,
    volumeMatch: PersistentVolumeMatcher.VolumeMatch): InstanceOp = {

    val currentTaskId = reservedInstance.appTask.taskId

    // The new taskId is based on the previous one. The previous taskId can denote either
    // 1. a resident task that was created with a previous version. In this case, both reservation label and taskId are
    //    perfectly normal taskIds.
    // 2. a task that was created to hold a reservation in 1.5 or later, this still is a completely normal taskId.
    // 3. an existing reservation from a previous version of Marathon, or a new reservation created in 1.5 or later. In
    //    this case, this is also a normal taskId
    // 4. a resident task that was created with 1.5 or later. In this case, the taskId has an appended launch attempt,
    //    a number prefixed with a separator.
    // All of these cases are handled in one way: by creating a new taskId for a resident task based on the previous
    // one. The used function will increment the attempt counter if it exists, of append a 1 to denote the first attempt
    // in version 1.5.
    val newTaskId = Task.Id.forResidentTask(currentTaskId)
    val (taskInfo, networkInfo) = new TaskBuilder(spec, newTaskId, config, runSpecTaskProc)
      .build(offer, resourceMatch, Some(volumeMatch))

    // The agentInfo could have possibly changed after a reboot. See the docs for
    // InstanceUpdateOperation.LaunchOnReservation for more details
    val agentInfo = Instance.AgentInfo(offer)
    val stateOp = InstanceUpdateOperation.LaunchOnReservation(
      reservedInstance.instanceId,
      newTaskId,
      runSpecVersion = spec.version,
      timestamp = clock.now(),
      status = Task.Status(
        stagedAt = clock.now(),
        condition = Condition.Created,
        networkInfo = networkInfo
      ),
      networkInfo.hostPorts,
      agentInfo)

    taskOperationFactory.launchOnReservation(taskInfo, stateOp, reservedInstance)
  }

  private[this] def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    runSpec: RunSpec,
    offer: Mesos.Offer,
    resourceMatch: ResourceMatcher.ResourceMatch): InstanceOp = {

    val localVolumes: Seq[(DiskSource, Task.LocalVolume)] =
      resourceMatch.localVolumes.map {
        case (source, volume) =>
          (source, Task.LocalVolume(Task.LocalVolumeId(runSpec.id, volume), volume))
      }
    val persistentVolumeIds = localVolumes.map { case (_, localVolume) => localVolume.id }
    val now = clock.now()
    val timeout = Task.Reservation.Timeout(
      initiated = now,
      deadline = now + config.taskReservationTimeout().millis,
      reason = Task.Reservation.Timeout.Reason.ReservationTimeout
    )
    val agentInfo = Instance.AgentInfo(offer)
    val hostPorts = resourceMatch.hostPorts.flatten
    val networkInfo = NetworkInfo(offer.getHostname, hostPorts, ipAddresses = Nil)

    // The first taskId does not have an attempt count - this is only the task created to hold the reservation and it
    // will be replaced with a new task once we launch on an existing reservation this way, the reservation will be
    // labeled with a taskId that does not relate to a task existing in Mesos (previously, Marathon reused taskIds so
    // there was always a 1:1 correlation from reservation to taskId)
    val taskId = Task.Id.forRunSpec(runSpec.id)
    val reservationLabels = TaskLabels.labelsForTask(frameworkId, taskId)
    val reservation = Task.Reservation(persistentVolumeIds, Task.Reservation.State.New(timeout = Some(timeout)))
    val task = Task.Reserved(
      taskId = taskId,
      reservation = reservation,
      status = Task.Status(
        stagedAt = now,
        condition = Condition.Reserved,
        networkInfo = networkInfo
      ),
      runSpecVersion = runSpec.version
    )
    val instance = Instance(
      instanceId = task.taskId.instanceId,
      agentInfo = agentInfo,
      state = InstanceState(
        condition = Condition.Reserved,
        since = now,
        activeSince = None,
        healthy = None
      ),
      tasksMap = Map(task.taskId -> task),
      runSpecVersion = runSpec.version,
      unreachableStrategy = runSpec.unreachableStrategy
    )
    val stateOp = InstanceUpdateOperation.Reserve(instance)
    taskOperationFactory.reserveAndCreateVolumes(reservationLabels, stateOp, resourceMatch.resources, localVolumes)
  }

  def combine(processors: Seq[RunSpecTaskProcessor]): RunSpecTaskProcessor = new RunSpecTaskProcessor {
    override def taskInfo(runSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {
      processors.foreach(_.taskInfo(runSpec, builder))
    }
    override def taskGroup(podSpec: PodSpec, executor: ExecutorInfo.Builder, taskGroup: TaskGroupInfo.Builder): Unit = {
      processors.foreach(_.taskGroup(podSpec, executor, taskGroup))
    }
  }
}

object InstanceOpFactoryImpl {

  protected[impl] def ephemeralPodInstance(
    pod: PodDefinition,
    agentInfo: Instance.AgentInfo,
    taskIDs: Seq[Task.Id],
    hostPorts: Seq[Option[Int]],
    instanceId: Instance.Id)(implicit clock: Clock): Instance = {

    val reqPortsByCTName: Seq[(String, Option[Int])] = pod.containers.flatMap { ct =>
      ct.endpoints.map { ep =>
        ct.name -> ep.hostPort
      }
    }

    val totalRequestedPorts = reqPortsByCTName.size
    assume(totalRequestedPorts == hostPorts.size, s"expected that number of allocated ports ${hostPorts.size}" +
      s" would equal the number of requested host ports $totalRequestedPorts")

    assume(!hostPorts.flatten.contains(0), "expected that all dynamic host ports have been allocated")

    val since = clock.now()

    val allocPortsByCTName: Seq[(String, Int)] = reqPortsByCTName.zip(hostPorts).collect {
      case ((name, Some(_)), Some(allocatedPort)) => name -> allocatedPort
    }(collection.breakOut)

    Instance(
      instanceId,
      agentInfo = agentInfo,
      state = InstanceState(Condition.Created, since, activeSince = None, healthy = None),
      tasksMap = taskIDs.map { taskId =>
        // the task level host ports are needed for fine-grained status/reporting later on
        val taskHostPorts: Seq[Int] = taskId.containerName.map { ctName =>
          allocPortsByCTName.withFilter{ case (name, port) => name == ctName }.map(_._2)
        }.getOrElse(Seq.empty[Int])

        val networkInfo = NetworkInfo(agentInfo.host, taskHostPorts, ipAddresses = Nil)
        val task = Task.LaunchedEphemeral(
          taskId = taskId,
          runSpecVersion = pod.version,
          status = Task.Status(stagedAt = since, condition = Condition.Created, networkInfo = networkInfo)
        )
        task.taskId -> task
      }(collection.breakOut),
      runSpecVersion = pod.version,
      unreachableStrategy = pod.unreachableStrategy
    )
  } // inferPodInstance
}
