package mesosphere.marathon
package core.launcher.impl

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.{ AgentInfo, InstanceState }
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance }
import mesosphere.marathon.core.launcher.{ InstanceOp, InstanceOpFactory, OfferMatchResult }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ ApplicationSpec, PodSpec }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.{ NoOfferMatchReason, PersistentVolumeMatcher, ResourceMatchResponse, ResourceMatcher, RunSpecOfferMatcher, TaskBuilder, TaskGroupBuilder }
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ ExecutorInfo, TaskGroupInfo, TaskInfo }
import org.apache.mesos.{ Protos => Mesos }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class InstanceOpFactoryImpl(
  config: MarathonConf,
  pluginManager: PluginManager = PluginManager.None)(implicit clock: Clock)
    extends InstanceOpFactory {

  import InstanceOpFactoryImpl._

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] val taskOperationFactory = {
    val principalOpt = config.mesosAuthenticationPrincipal.get
    val roleOpt = config.mesosRole.get

    new InstanceOpFactoryHelper(principalOpt, roleOpt)
  }

  private[this] lazy val runSpecTaskProc: RunSpecTaskProcessor = combine(
    pluginManager.plugins[RunSpecTaskProcessor].toIndexedSeq)

  override def matchOfferRequest(request: InstanceOpFactory.Request): OfferMatchResult = {
    log.debug("matchOfferRequest")

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
      config.envVarsPrefix.get)

    val matchedOffer =
      RunSpecOfferMatcher.matchOffer(pod, request.offer, request.instances, builderConfig.acceptedResourceRoles)

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
      RunSpecOfferMatcher.matchOffer(app, offer, instances.values.toIndexedSeq, config.defaultAcceptedResourceRolesSet)
    matchResponse match {
      case matches: ResourceMatchResponse.Match =>
        val taskBuilder = new TaskBuilder(app, Task.Id.forRunSpec, config, runSpecTaskProc)
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
     *  We ned to reserve resources and receive an offer that has matching resources
     *  - schedule a ReserveAndCreate TaskOp
     */

    def maybeLaunchOnReservation: Option[OfferMatchResult] = if (needToLaunch) {
      val maybeVolumeMatch = PersistentVolumeMatcher.matchVolumes(offer, request.reserved)

      maybeVolumeMatch.map { volumeMatch =>

        // we must not consider the volumeMatch's Reserved instance because that would lead to a violation of constraints
        // by the Reserved instance that we actually want to launch
        val instancesToConsiderForConstraints: Stream[Instance] =
          instances.valuesIterator.toStream.filterNotAs(_.instanceId != volumeMatch.instance.instanceId)

        // resources are reserved for this role, so we only consider those resources
        val rolesToConsider = config.mesosRole.get.toSet
        val reservationLabels = TaskLabels.labelsForTask(request.frameworkId, volumeMatch.instance.appTask.taskId).labels
        val resourceMatchResponse =
          ResourceMatcher.matchResources(
            offer, runSpec, instancesToConsiderForConstraints,
            ResourceSelector.reservedWithLabels(rolesToConsider, reservationLabels)
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
        log.warn(s"Will never match for ${runSpec.id}. The runSpec is not configured to accept unreserved resources.")
      }

      val resourceMatchResponse =
        ResourceMatcher.matchResources(offer, runSpec, instances.valuesIterator.toStream, ResourceSelector.reservable)
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
        log.warn("No need to reserve or launch and offer request isForResidentRunSpec")
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

    val reuseOldTaskId = (_: PathId) => reservedInstance.tasksMap.headOption.map(_._1).getOrElse {
      throw new IllegalStateException(s"${reservedInstance.instanceId} does not contain any task")
    }
    // create a TaskBuilder that used the id of the existing task as id for the created TaskInfo
    val (taskInfo, networkInfo) = new TaskBuilder(spec, reuseOldTaskId, config, runSpecTaskProc).build(offer, resourceMatch, Some(volumeMatch))
    val stateOp = InstanceUpdateOperation.LaunchOnReservation(
      reservedInstance.instanceId,
      runSpecVersion = spec.version,
      timestamp = clock.now(),
      status = Task.Status(
        stagedAt = clock.now(),
        condition = Condition.Created,
        networkInfo = networkInfo
      ),
      networkInfo.hostPorts)

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
    val task = Task.Reserved(
      taskId = Task.Id.forRunSpec(runSpec.id),
      reservation = Task.Reservation(persistentVolumeIds, Task.Reservation.State.New(timeout = Some(timeout))),
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
      runSpecVersion = runSpec.version
    )
    val stateOp = InstanceUpdateOperation.Reserve(instance)
    taskOperationFactory.reserveAndCreateVolumes(frameworkId, stateOp, resourceMatch.resources, localVolumes)
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
