package mesosphere.marathon
package core.launcher.impl

import java.time.Clock

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{Instance, LocalVolume, LocalVolumeId, Reservation}
import mesosphere.marathon.core.launcher.{InstanceOp, InstanceOpFactory, OfferMatchResult}
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.metrics.Metrics
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
    metrics: Metrics,
    config: MarathonConf,
    pluginManager: PluginManager = PluginManager.None)(implicit clock: Clock)
  extends InstanceOpFactory with StrictLogging {

  private[this] val instanceOperationFactory = {
    val principalOpt = config.mesosAuthenticationPrincipal.toOption
    val roleOpt = config.mesosRole.toOption

    new InstanceOpFactoryHelper(metrics, principalOpt, roleOpt)
  }

  private[this] val schedulerPlugins: Seq[SchedulerPlugin] = pluginManager.plugins[SchedulerPlugin]

  private[this] lazy val runSpecTaskProc: RunSpecTaskProcessor = combine(
    pluginManager.plugins[RunSpecTaskProcessor].toIndexedSeq)

  override def matchOfferRequest(request: InstanceOpFactory.Request): OfferMatchResult = {
    logger.debug(s"Matching offer ${request.offer.getId}")

    request.scheduledInstances.head match {
      case scheduledInstance @ Instance(_, _, _, _, app: AppDefinition, _) =>
        if (app.isResident) inferForResidents(request, scheduledInstance)
        else inferNormalTaskOp(app, request.instances, request.offer, request.localRegion, scheduledInstance)
      case scheduledInstance @ Instance(_, _, _, _, pod: PodDefinition, _) =>
        if (pod.isResident) inferForResidents(request, scheduledInstance)
        else inferPodInstanceOp(pod, request.instances, request.offer, request.localRegion, scheduledInstance)
      case Instance(_, _, _, _, runSpec, _) =>
        throw new IllegalArgumentException(s"unsupported runSpec object ${runSpec}")
    }
  }

  protected def inferPodInstanceOp(
    pod: PodDefinition,
    runningInstances: Seq[Instance],
    offer: Mesos.Offer,
    localRegion: Option[Region],
    scheduledInstance: Instance): OfferMatchResult = {

    logger.debug(s"Infer for ephemeral pod ${scheduledInstance.instanceId}")

    val builderConfig = TaskGroupBuilder.BuilderConfig(
      config.defaultAcceptedResourceRolesSet,
      config.envVarsPrefix.toOption,
      config.mesosBridgeName())

    val matchedOffer =
      RunSpecOfferMatcher.matchOffer(pod, offer, runningInstances,
        builderConfig.acceptedResourceRoles, config, schedulerPlugins, localRegion)

    matchedOffer match {
      case matches: ResourceMatchResponse.Match =>
        val instanceId = scheduledInstance.instanceId
        val taskIds = if (scheduledInstance.tasksMap.nonEmpty) {
          scheduledInstance.tasksMap.keysIterator.map(Task.Id.nextIncarnationFor).to[Seq]
        } else {
          pod.containers.map { container => Task.Id(instanceId, Some(container)) }
        }
        val (executorInfo, groupInfo, networkInfos) = TaskGroupBuilder.build(pod, offer,
          instanceId, taskIds, builderConfig, runSpecTaskProc, matches.resourceMatch, None)

        val agentInfo = Instance.AgentInfo(offer)
        val taskIDs: Seq[Task.Id] = groupInfo.getTasksList.map { t => Task.Id.parse(t.getTaskId) }(collection.breakOut)
        val now = clock.now()
        val tasks = Tasks.provisioned(taskIDs, networkInfos, pod.version, now)
        val stateOp = InstanceUpdateOperation.Provision(instanceId, agentInfo, pod, tasks, now)
        val instanceOp = instanceOperationFactory.provision(executorInfo, groupInfo, scheduledInstance.instanceId, stateOp)
        OfferMatchResult.Match(pod, offer, instanceOp, now)
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
        val taskId = scheduledInstance.tasksMap.headOption match {
          case Some((id, _)) => Task.Id.nextIncarnationFor(id)
          case None => Task.Id(scheduledInstance.instanceId)
        }
        val taskBuilder = new TaskBuilder(app, taskId, config, runSpecTaskProc)
        val (taskInfo, networkInfo) = taskBuilder.build(offer, matches.resourceMatch, None)

        val agentInfo = AgentInfo(offer)

        val now = clock.now()
        val task = Tasks.provisioned(Seq(taskId), Map(taskId -> networkInfo), app.version, now)
        val stateOp = InstanceUpdateOperation.Provision(scheduledInstance.instanceId, agentInfo, scheduledInstance.runSpec, task, now)
        val instanceOp = instanceOperationFactory.provision(taskInfo, stateOp)

        OfferMatchResult.Match(app, offer, instanceOp, now)
      case matchesNot: ResourceMatchResponse.NoMatch => OfferMatchResult.NoMatch(app, offer, matchesNot.reasons, clock.now())
    }
  }

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
  private def maybeLaunchOnReservation(request: InstanceOpFactory.Request): Option[OfferMatchResult] = if (request.hasWaitingReservations) {
    val InstanceOpFactory.Request(offer, instances, _, localRegion) = request

    val maybeVolumeMatch = PersistentVolumeMatcher.matchVolumes(offer, request.reserved)

    maybeVolumeMatch.map { volumeMatch =>
      val runSpec = volumeMatch.instance.runSpec
      logger.debug(s"Need to launch on reservation for ${runSpec.id}, version ${runSpec.version}")

      // The volumeMatch identified a specific instance that matches the volume's reservation labels.
      // This is the instance we want to launch. However, when validating constraints, we need to exclude that one
      // instance: it would be considered as an instance on that agent, and would violate e.g. a hostname:unique
      // constraint although it is just a placeholder for the instance that will be launched.
      val instancesToConsiderForConstraints: Stream[Instance] =
        instances.valuesIterator.toStream.filterAs(_.instanceId != volumeMatch.instance.instanceId)

      // resources are reserved for this role, so we only consider those resources
      val rolesToConsider = config.mesosRole.toOption.toSet
      // TODO(karsten): We should pass the instance id to the resource matcher instead. See MARATHON-8517.
      val reservationId = Reservation.Id(volumeMatch.instance.instanceId)
      val reservationLabels = TaskLabels.labelsForTask(request.frameworkId, reservationId).labels
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
          val instanceOp = launchOnReservation(runSpec, offer, volumeMatch.instance, matches.resourceMatch, volumeMatch)
          OfferMatchResult.Match(runSpec, request.offer, instanceOp, clock.now())
        case matchesNot: ResourceMatchResponse.NoMatch =>
          OfferMatchResult.NoMatch(runSpec, request.offer, matchesNot.reasons, clock.now())
      }
    }
  } else None

  @SuppressWarnings(Array("TraversableHead"))
  private def maybeReserveAndCreateVolumes(request: InstanceOpFactory.Request): Option[OfferMatchResult] = {
    val InstanceOpFactory.Request(offer, instances, scheduledInstances, localRegion) = request
    scheduledInstances.find(!_.hasReservation).map { firstScheduledInstance =>
      val runSpec = firstScheduledInstance.runSpec

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
          val instanceOp = reserveAndCreateVolumes(request.frameworkId, runSpec, offer, matches.resourceMatch, firstScheduledInstance)
          OfferMatchResult.Match(runSpec, request.offer, instanceOp, clock.now())
        case matchesNot: ResourceMatchResponse.NoMatch =>
          OfferMatchResult.NoMatch(runSpec, request.offer, matchesNot.reasons, clock.now())
      }
    }
  }

  private[this] def inferForResidents(request: InstanceOpFactory.Request, scheduledInstance: Instance): OfferMatchResult = {
    maybeLaunchOnReservation(request)
      .orElse(maybeReserveAndCreateVolumes(request))
      .getOrElse {
        logger.warn("No need to reserve or launch and offer request isForResidentRunSpec")
        OfferMatchResult.NoMatch(scheduledInstance.runSpec, request.offer,
          Seq(NoOfferMatchReason.NoCorrespondingReservationFound), clock.now())
      }
  }

  private[this] def launchOnReservation(
    spec: RunSpec,
    offer: Mesos.Offer,
    reservedInstance: Instance,
    resourceMatch: ResourceMatcher.ResourceMatch,
    volumeMatch: PersistentVolumeMatcher.VolumeMatch): InstanceOp = {

    val agentInfo = Instance.AgentInfo(offer)

    spec match {
      case app: AppDefinition =>
        logger.debug(s"Launching resident app ${reservedInstance.instanceId} on reservation ${reservedInstance.reservation}")
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
        val taskIds: Seq[Task.Id] = if (reservedInstance.tasksMap.nonEmpty) {
          reservedInstance.tasksMap.keysIterator.map(Task.Id.nextIncarnationFor).to[Seq]
        } else {
          Seq(Task.Id(reservedInstance.instanceId))
        }
        val newTaskId = taskIds.headOption.getOrElse(throw new IllegalStateException(s"Expecting to have a task id present when creating instance for app ${app.id} from instance $reservedInstance"))

        val (taskInfo, networkInfo) =
          new TaskBuilder(app, newTaskId, config, runSpecTaskProc)
            .build(offer, resourceMatch, Some(volumeMatch))

        val now = clock.now()
        val provisionedTasks = Tasks.provisioned(Seq(newTaskId), Map(newTaskId -> networkInfo), app.version, now)
        val stateOp = InstanceUpdateOperation.Provision(reservedInstance.instanceId, agentInfo, app, provisionedTasks, now)

        instanceOperationFactory.launchOnReservation(taskInfo, stateOp, reservedInstance)

      case pod: PodDefinition =>
        logger.debug(s"Launching resident pod ${reservedInstance.instanceId} on reservation ${reservedInstance.reservation}")
        val builderConfig = TaskGroupBuilder.BuilderConfig(
          config.defaultAcceptedResourceRolesSet,
          config.envVarsPrefix.toOption,
          config.mesosBridgeName())

        val instanceId = reservedInstance.instanceId
        val taskIds = if (reservedInstance.tasksMap.nonEmpty) {
          reservedInstance.tasksMap.keys.to[Seq]
        } else {
          pod.containers.map { container =>
            Task.Id(reservedInstance.instanceId, Some(container))
          }
        }
        val oldToNewTaskIds: Map[Task.Id, Task.Id] = taskIds.map { taskId =>
          taskId -> Task.Id.nextIncarnationFor(taskId)
        }(collection.breakOut)

        val containerNameToTaskId: Map[String, Task.Id] = oldToNewTaskIds.values.map {
          case taskId @ Task.TaskIdWithIncarnation(_, Some(containerName), _) => containerName -> taskId
          case taskId => throw new IllegalStateException(s"failed to extract a container name from the task id $taskId")
        }(collection.breakOut)
        val podContainerTaskIds: Seq[Task.Id] = pod.containers.map { container =>
          containerNameToTaskId.getOrElse(container.name, throw new IllegalStateException(
            s"failed to get a task ID for the given container name: ${container.name}"))
        }

        val (executorInfo, groupInfo, networkInfos) = TaskGroupBuilder.build(pod, offer,
          instanceId, podContainerTaskIds, builderConfig, runSpecTaskProc, resourceMatch, Some(volumeMatch))

        val now = clock.now()
        val provisionedTasks = Tasks.provisioned(podContainerTaskIds, networkInfos, pod.version, now)
        val stateOp = InstanceUpdateOperation.Provision(reservedInstance.instanceId, agentInfo, pod, provisionedTasks, now)

        instanceOperationFactory.launchOnReservation(executorInfo, groupInfo, stateOp, reservedInstance)
    }
  }

  private[this] def reserveAndCreateVolumes(
    frameworkId: FrameworkId,
    runSpec: RunSpec,
    offer: Mesos.Offer,
    resourceMatch: ResourceMatcher.ResourceMatch,
    scheduledInstance: Instance): InstanceOp = {

    logger.debug(s"Reserved for ${scheduledInstance.instanceId} resources ${resourceMatch.resources}")

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

    val reservationLabels = TaskLabels.labelsForTask(frameworkId, Reservation.Id(scheduledInstance.instanceId))
    val stateOp = InstanceUpdateOperation.Reserve(Instance.scheduled(scheduledInstance, reservation, agentInfo))
    instanceOperationFactory.reserveAndCreateVolumes(reservationLabels, stateOp, resourceMatch.resources, localVolumes)
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
}
