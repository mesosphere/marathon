package mesosphere.marathon
package core.launcher.impl

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.{AgentInfo, InstanceState}
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{Instance, LocalVolume, LocalVolumeId, Reservation}
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.plugin.scheduler.SchedulerPlugin
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ApplicationSpec, PodSpec}
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.{DiskResourceMatch, NoOfferMatchReason, PersistentVolumeMatcher, ResourceMatchResponse, ResourceMatcher, RunSpecOfferMatcher, TaskBuilder, TaskGroupBuilder}
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ExecutorInfo, TaskGroupInfo, TaskInfo}
import org.apache.mesos.{Protos => Mesos}

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
          request.scheduledInstances.headOption.map { scheduledInstance =>
            inferNormalTaskOp(app, request.instances, request.offer, request.localRegion, scheduledInstance)
          }.getOrElse(OfferMatchResult.NoMatch(app, request.offer, Seq.empty, clock.now()))
        }
      case pod: PodDefinition =>
        if (request.isForResidentRunSpec) {
          inferForResidents(pod, request)
        } else {
          request.scheduledInstances.headOption.map { scheduledInstance =>
            inferPodInstanceOp(pod, request.instances, request.offer, request.localRegion, scheduledInstance)
          }.getOrElse(OfferMatchResult.NoMatch(pod, request.offer, Seq.empty, clock.now()))
        }
      case _ =>
        throw new IllegalArgumentException(s"unsupported runSpec object ${request.runSpec}")
    }
  }

  protected def inferPodInstanceOp(
    pod: PodDefinition,
    runningInstances: Seq[Instance],
    offer: Mesos.Offer,
    localRegion: Option[Region],
    scheduledInstance: Instance): OfferMatchResult = {

    val builderConfig = TaskGroupBuilder.BuilderConfig(
      config.defaultAcceptedResourceRolesSet,
      config.envVarsPrefix.get,
      config.mesosBridgeName())

    val matchedOffer =
      RunSpecOfferMatcher.matchOffer(pod, offer, runningInstances,
        builderConfig.acceptedResourceRoles, config, schedulerPlugins, localRegion)

    matchedOffer match {
      case matches: ResourceMatchResponse.Match =>
        val instanceId = scheduledInstance.instanceId
        val taskIds = pod.containers.map { container =>
          Task.Id.forInstanceId(instanceId, Some(container))
        }
        val (executorInfo, groupInfo, hostPorts) = TaskGroupBuilder.build(pod, offer,
          instanceId, taskIds, builderConfig, runSpecTaskProc, matches.resourceMatch, None)

        val agentInfo = Instance.AgentInfo(offer)
        val taskIDs: Seq[Task.Id] = groupInfo.getTasksList.map { t => Task.Id(t.getTaskId) }(collection.breakOut)
        val instance = ephemeralPodInstance(pod, agentInfo, taskIDs, hostPorts, instanceId)
        val instanceOp = taskOperationFactory.provision(executorInfo, groupInfo, Instance.LaunchRequest(instance))
        OfferMatchResult.Match(pod, offer, instanceOp, clock.now())
      case matchesNot: ResourceMatchResponse.NoMatch =>
        OfferMatchResult.NoMatch(pod, offer, matchesNot.reasons, clock.now())
    }
  }

  /**
    * Matches offer and constructs provision operation.
    *
    * @param app The app definition.
    * @param runningInstances A list of running instances, ie we accepted an offer for them.
    * @param offer The Mesos offer.
    * @param localRegion Current region where Mesos master is running. See [[MarathonScheduler.getLocalRegion]].
    * @return The match result including the state opration that will update the instance from scheduled to provisioned.
    */
  private[this] def inferNormalTaskOp(
    app: AppDefinition,
    runningInstances: Seq[Instance],
    offer: Mesos.Offer,
    localRegion: Option[Region],
    scheduledInstance: Instance): OfferMatchResult = {

    val matchResponse =
      RunSpecOfferMatcher.matchOffer(app, offer, runningInstances,
        config.defaultAcceptedResourceRolesSet, config, schedulerPlugins, localRegion)
    matchResponse match {
      case matches: ResourceMatchResponse.Match =>
        val now = clock.now()

        val taskId = Task.Id.forInstanceId(scheduledInstance.instanceId, None)
        val taskBuilder = new TaskBuilder(app, taskId, config, runSpecTaskProc)
        val (taskInfo, networkInfo) = taskBuilder.build(offer, matches.resourceMatch, None)
        val task = Task(
          taskId = taskId,
          runSpecVersion = app.version,
          status = Task.Status(
            stagedAt = now,
            condition = Condition.Created,
            networkInfo = networkInfo
          )
        )

        val agentInfo = AgentInfo(offer)

        val tasksMap = Map(task.taskId -> task)
        val state = Instance.InstanceState(Condition.Provisioned, now, None, None)
        val provisionedInstance = scheduledInstance.copy(
          agentInfo = Some(agentInfo),
          state = state,
          tasksMap = tasksMap,
          runSpecVersion = app.version // Note: The app version might change.
        )
        val instanceOp = taskOperationFactory.provision(taskInfo, task, provisionedInstance)

        OfferMatchResult.Match(app, offer, instanceOp, clock.now())
      case matchesNot: ResourceMatchResponse.NoMatch => OfferMatchResult.NoMatch(app, offer, matchesNot.reasons, clock.now())
    }
  }

  private[this] def inferForResidents(spec: RunSpec, request: InstanceOpFactory.Request): OfferMatchResult = {
    val InstanceOpFactory.Request(runSpec, offer, instances, scheduledInstances, localRegion) = request

    val needToLaunch = request.hasWaitingReservations
    val needToReserve = scheduledInstances.nonEmpty

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
      logger.debug(s"Need to launch on reservation for ${runSpec.id}, version ${runSpec.version}")
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
            ResourceSelector.reservedWithLabels(rolesToConsider, reservationLabels), config,
            schedulerPlugins,
            localRegion,
            request.reserved
          )

        resourceMatchResponse match {
          case matches: ResourceMatchResponse.Match =>
            val instanceOp = launchOnReservation(spec, offer, volumeMatch.instance, matches.resourceMatch, volumeMatch)
            OfferMatchResult.Match(spec, request.offer, instanceOp, clock.now())
          case matchesNot: ResourceMatchResponse.NoMatch =>
            OfferMatchResult.NoMatch(spec, request.offer, matchesNot.reasons, clock.now())
        }
      }
    } else None

    @SuppressWarnings(Array("TraversableHead"))
    def maybeReserveAndCreateVolumes: Option[OfferMatchResult] = if (needToReserve) {
      logger.debug(s"Need to reserve for ${runSpec.id}, version ${runSpec.version}")
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
        ResourceMatcher.matchResources(offer, runSpec, instances.valuesIterator.toStream,
          ResourceSelector.reservable, config, schedulerPlugins, localRegion)
      resourceMatchResponse match {
        case matches: ResourceMatchResponse.Match =>
          val instanceOp = reserveAndCreateVolumes(request.frameworkId, runSpec, offer, matches.resourceMatch, scheduledInstances.head)
          Some(OfferMatchResult.Match(spec, request.offer, instanceOp, clock.now()))
        case matchesNot: ResourceMatchResponse.NoMatch =>
          Some(OfferMatchResult.NoMatch(spec, request.offer, matchesNot.reasons, clock.now()))
      }
    } else None

    maybeLaunchOnReservation
      .orElse(maybeReserveAndCreateVolumes)
      .getOrElse {
        logger.warn("No need to reserve or launch and offer request isForResidentRunSpec")
        OfferMatchResult.NoMatch(spec, request.offer,
          Seq(NoOfferMatchReason.NoCorrespondingReservationFound), clock.now())
      }
  }

  private[this] def launchOnReservation(
    spec: RunSpec,
    offer: Mesos.Offer,
    reservedInstance: Instance,
    resourceMatch: ResourceMatcher.ResourceMatch,
    volumeMatch: PersistentVolumeMatcher.VolumeMatch): InstanceOp = {

    // The agentInfo could have possibly changed after a reboot. See the docs for
    // InstanceUpdateOperation.LaunchOnReservation for more details
    val agentInfo = Instance.AgentInfo(offer)

    spec match {
      case app: AppDefinition =>
        val taskId = reservedInstance.appTask.taskId

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
        val newTaskId = Task.Id.forResidentTask(taskId)

        val (taskInfo, networkInfo) =
          new TaskBuilder(app, newTaskId, config, runSpecTaskProc)
            .build(offer, resourceMatch, Some(volumeMatch))

        val now = clock.now()
        val stateOp = InstanceUpdateOperation.LaunchOnReservation(
          instanceId = reservedInstance.instanceId,
          oldToNewTaskIds = Map(taskId -> newTaskId),
          runSpecVersion = spec.version,
          timestamp = now,
          statuses = Map(newTaskId -> Task.Status(
            stagedAt = now,
            condition = Condition.Created,
            networkInfo = networkInfo)),
          Map(newTaskId -> networkInfo.hostPorts),
          agentInfo)

        taskOperationFactory.launchOnReservation(taskInfo, stateOp, reservedInstance)

      case pod: PodDefinition =>
        val builderConfig = TaskGroupBuilder.BuilderConfig(
          config.defaultAcceptedResourceRolesSet,
          config.envVarsPrefix.get,
          config.mesosBridgeName())

        val instanceId = reservedInstance.instanceId
        val taskIds = reservedInstance.tasksMap.keys
        val oldToNewTaskIds: Map[Task.Id, Task.Id] = taskIds.map { taskId =>
          taskId -> Task.Id.forResidentTask(taskId)
        }(collection.breakOut)

        val containerNameToTaskId: Map[String, Task.Id] = oldToNewTaskIds.values.map {
          case taskId @ Task.ResidentTaskId(_, Some(containerName), _) => containerName -> taskId
          case taskId => throw new IllegalStateException(s"failed to extract a container name from the task id $taskId")
        }(collection.breakOut)
        val podContainerTaskIds: Seq[Task.Id] = pod.containers.map { container =>
          containerNameToTaskId.getOrElse(container.name, throw new IllegalStateException(
            s"failed to get a task ID for the given container name: ${container.name}"))
        }

        val (executorInfo, groupInfo, hostPorts) = TaskGroupBuilder.build(pod, offer,
          instanceId, podContainerTaskIds, builderConfig, runSpecTaskProc, resourceMatch, Some(volumeMatch))

        val networkInfos = podTaskNetworkInfos(pod, agentInfo, podContainerTaskIds, hostPorts)
        val now = clock.now()
        val statuses = networkInfos.map {
          case (newTaskId, networkInfo) =>
            newTaskId -> Task.Status(stagedAt = now, condition = Condition.Created, networkInfo = networkInfo)
        }
        val taskHostPorts = networkInfos.map {
          case (newTaskId, networkInfo) =>
            newTaskId -> networkInfo.hostPorts
        }

        val stateOp = InstanceUpdateOperation.LaunchOnReservation(
          instanceId = reservedInstance.instanceId,
          oldToNewTaskIds = oldToNewTaskIds,
          runSpecVersion = pod.version,
          timestamp = now,
          statuses = statuses,
          hostPorts = taskHostPorts,
          agentInfo = agentInfo
        )

        taskOperationFactory.launchOnReservation(executorInfo, groupInfo, stateOp, reservedInstance)
    }
  }

  private[this] def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    runSpec: RunSpec,
    offer: Mesos.Offer,
    resourceMatch: ResourceMatcher.ResourceMatch,
    scheduledInstance: Instance): InstanceOp = {

    val localVolumes: Seq[InstanceOpFactory.OfferedVolume] =
      resourceMatch.localVolumes.map {
        case DiskResourceMatch.ConsumedVolume(providerId, source, VolumeWithMount(volume, mount)) =>
          val localVolume = LocalVolume(LocalVolumeId(runSpec.id, volume, mount), volume, mount)
          InstanceOpFactory.OfferedVolume(providerId, source, localVolume)
      }

    val persistentVolumeIds = localVolumes.map(_.volume.id)
    val now = clock.now()
    val timeout = Reservation.Timeout(
      initiated = now,
      deadline = now + config.taskReservationTimeout().millis,
      reason = Reservation.Timeout.Reason.ReservationTimeout)
    val state = Reservation.State.New(timeout = Some(timeout))
    val reservation = Reservation(persistentVolumeIds, state)
    val agentInfo = Instance.AgentInfo(offer)

    val (reservationLabels, stateOp) = runSpec match {
      case _: AppDefinition =>
        val hostPorts = resourceMatch.hostPorts.flatten
        val networkInfo = NetworkInfo(offer.getHostname, hostPorts, ipAddresses = Nil)

        // The first taskId does not have an attempt count - this is only the task created to hold the reservation and it
        // will be replaced with a new task once we launch on an existing reservation this way, the reservation will be
        // labeled with a taskId that does not relate to a task existing in Mesos (previously, Marathon reused taskIds so
        // there was always a 1:1 correlation from reservation to taskId)
        val taskId = Task.Id.forInstanceId(scheduledInstance.instanceId, None)
        val reservationLabels = TaskLabels.labelsForTask(frameworkId, taskId)
        val task = Task(
          taskId = taskId,
          status = Task.Status(
            stagedAt = now,
            condition = Condition.Reserved,
            networkInfo = networkInfo
          ),
          runSpecVersion = runSpec.version
        )

        val instance = Instance(
          instanceId = scheduledInstance.instanceId,
          agentInfo = Some(agentInfo),
          state = InstanceState(
            condition = Condition.Reserved,
            since = now,
            activeSince = None,
            healthy = None
          ),
          tasksMap = Map(task.taskId -> task),
          runSpecVersion = runSpec.version,
          unreachableStrategy = runSpec.unreachableStrategy,
          reservation = Some(reservation)
        )
        val stateOp = InstanceUpdateOperation.Reserve(instance)
        (reservationLabels, stateOp)

      case pod: PodDefinition =>
        val instanceId = scheduledInstance.instanceId
        val taskIds = pod.containers.map { container =>
          Task.Id.forInstanceId(instanceId, Some(container))
        }

        val networkInfos = podTaskNetworkInfos(pod, agentInfo, taskIds, resourceMatch.hostPorts)
        val reservationLabels = TaskLabels.labelsForTask(
          frameworkId,
          taskIds.headOption.getOrElse(throw new IllegalStateException("pod does not have any container")))

        val tasks = taskIds.map { taskId =>
          Task(
            taskId = taskId,
            status = Task.Status(
              stagedAt = now,
              condition = Condition.Reserved,
              networkInfo = networkInfos.getOrElse(
                taskId, throw new Exception("failed to retrieve a task network info"))
            ),
            runSpecVersion = runSpec.version
          )
        }

        val instance = Instance(
          instanceId = instanceId,
          agentInfo = Some(agentInfo),
          state = InstanceState(
            condition = Condition.Reserved,
            since = now,
            activeSince = None,
            healthy = None
          ),
          tasksMap = tasks.map(t => t.taskId -> t)(collection.breakOut),
          runSpecVersion = runSpec.version,
          unreachableStrategy = runSpec.unreachableStrategy,
          reservation = Some(reservation)
        )
        val stateOp = InstanceUpdateOperation.Reserve(instance)
        (reservationLabels, stateOp)
    }
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

  protected[impl] def podTaskNetworkInfos(
    pod: PodDefinition,
    agentInfo: Instance.AgentInfo,
    taskIDs: Seq[Task.Id],
    hostPorts: Seq[Option[Int]]
  ): Map[Task.Id, NetworkInfo] = {

    val reqPortsByCTName: Seq[(String, Option[Int])] = pod.containers.flatMap { ct =>
      ct.endpoints.map { ep =>
        ct.name -> ep.hostPort
      }
    }

    val totalRequestedPorts = reqPortsByCTName.size
    assume(totalRequestedPorts == hostPorts.size, s"expected that number of allocated ports ${hostPorts.size}" +
      s" would equal the number of requested host ports $totalRequestedPorts")

    assume(!hostPorts.flatten.contains(0), "expected that all dynamic host ports have been allocated")

    val allocPortsByCTName: Seq[(String, Int)] = reqPortsByCTName.zip(hostPorts).collect {
      case ((name, Some(_)), Some(allocatedPort)) => name -> allocatedPort
    }(collection.breakOut)

    taskIDs.map { taskId =>
      // the task level host ports are needed for fine-grained status/reporting later on
      val taskHostPorts: Seq[Int] = taskId.containerName.map { ctName =>
        allocPortsByCTName.withFilter { case (name, port) => name == ctName }.map(_._2)
      }.getOrElse(Seq.empty[Int])

      val networkInfo = NetworkInfo(agentInfo.host, taskHostPorts, ipAddresses = Nil)
      taskId -> networkInfo
    }(collection.breakOut)
  }

  protected[impl] def ephemeralPodInstance(
    pod: PodDefinition,
    agentInfo: Instance.AgentInfo,
    taskIDs: Seq[Task.Id],
    hostPorts: Seq[Option[Int]],
    instanceId: Instance.Id)(implicit clock: Clock): Instance = {

    val since = clock.now()
    val taskNetworkInfos = podTaskNetworkInfos(pod, agentInfo, taskIDs, hostPorts)

    Instance(
      instanceId,
      agentInfo = Some(agentInfo),
      state = InstanceState(Condition.Created, since, activeSince = None, healthy = None),
      tasksMap = taskIDs.map { taskId =>
        // the task level host ports are needed for fine-grained status/reporting later on
        val networkInfo = taskNetworkInfos.getOrElse(
          taskId,
          throw new IllegalStateException("failed to retrieve a task network info"))
        val task = Task(
          taskId = taskId,
          runSpecVersion = pod.version,
          status = Task.Status(stagedAt = since, condition = Condition.Created, networkInfo = networkInfo)
        )
        task.taskId -> task
      }(collection.breakOut),
      runSpecVersion = pod.version,
      unreachableStrategy = pod.unreachableStrategy,
      reservation = None
    )
  }
}
