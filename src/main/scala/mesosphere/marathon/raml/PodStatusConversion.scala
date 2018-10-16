package mesosphere.marathon
package raml

import java.time.OffsetDateTime

import mesosphere.marathon.core.condition
import mesosphere.marathon.core.health.{MesosCommandHealthCheck, MesosHttpHealthCheck, MesosTcpHealthCheck, PortReference}
import mesosphere.marathon.core.instance
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.core.task
import mesosphere.marathon.raml.LocalVolumeConversion.localVolumeIdWrites
import mesosphere.marathon.stream.Implicits._

trait PodStatusConversion {

  import PodStatusConversion._

  def taskToContainerStatus(pod: PodDefinition, instance: Instance)(targetTask: task.Task): ContainerStatus = {
    val since = targetTask.status.startedAt.getOrElse(targetTask.status.stagedAt).toOffsetDateTime // TODO(jdef) inaccurate

    val maybeContainerSpec: Option[MesosContainer] = pod.container(targetTask.taskId)

    // possible that a new pod spec might not have a container with a name that was used in an old pod spec?
    val endpointStatus = endpointStatuses(pod, maybeContainerSpec, targetTask)

    // some other layer should provide termination history

    // if, for some very strange reason, we cannot determine the container name from the task ID then default to
    // the Mesos task ID itself
    val displayName: String = targetTask.taskId.containerName.getOrElse(targetTask.taskId.mesosTaskId.getValue)

    val resources: Option[Resources] = {
      targetTask.status.condition match {
        case condition.Condition.Staging |
          condition.Condition.Starting |
          condition.Condition.Running |
          condition.Condition.Unreachable |
          condition.Condition.Killing =>
          maybeContainerSpec.map(_.resources)
        case _ if instance.isReserved =>
          maybeContainerSpec.map(_.resources)
        case _ =>
          None
      }
    }

    // TODO(jdef) message
    ContainerStatus(
      name = displayName,
      status = condition.Condition.toMesosTaskStateOrStaging(targetTask.status.condition).toString,
      statusSince = since,
      containerId = targetTask.launchedMesosId.map(_.getValue),
      endpoints = endpointStatus,
      conditions = List(maybeHealthCondition(targetTask.status, maybeContainerSpec, endpointStatus, since, instance)).flatten,
      resources = resources,
      lastUpdated = since, // TODO(jdef) pods fixme
      lastChanged = since // TODO(jdef) pods.fixme
    )
  }

  /**
    * generate a pod instance status RAML for some instance.
    */
  implicit val podInstanceStatusRamlWriter: Writes[(PodDefinition, instance.Instance), PodInstanceStatus] = Writes { src =>

    val (pod, instance) = src

    assume(
      pod.id == instance.instanceId.runSpecId,
      s"pod id ${pod.id} should match spec id of the instance ${instance.instanceId.runSpecId}")

    val containerStatus: Seq[ContainerStatus] =
      instance.tasksMap.values.map(taskToContainerStatus(pod, instance))(collection.breakOut)
    val (derivedStatus: PodInstanceState, message: Option[String]) = podInstanceState(
      instance.state.condition, containerStatus)

    val networkStatus: Seq[NetworkStatus] = networkStatuses(instance.tasksMap.values.to[Seq])
    val resources: Resources = containerStatus.flatMap(_.resources).foldLeft(PodDefinition.DefaultExecutorResources) { (all, res) =>
      all.copy(cpus = all.cpus + res.cpus, mem = all.mem + res.mem, disk = all.disk + res.disk, gpus = all.gpus + res.gpus)
    }

    val localVolumes = instance.reservation.fold(Seq.empty[LocalVolumeId]) { reservation =>
      reservation.volumeIds.toRaml
    }

    // TODO(jdef) message, conditions: for example it would probably be nice to see a "healthy" condition here that
    // summarizes the conditions of the same name for each of the instance's containers.
    PodInstanceStatus(
      id = instance.instanceId.idString,
      status = derivedStatus,
      statusSince = instance.state.since.toOffsetDateTime,
      agentId = instance.agentInfo.flatMap(_.agentId),
      agentHostname = instance.hostname,
      agentRegion = instance.region,
      agentZone = instance.zone,
      resources = Some(resources),
      networks = networkStatus,
      containers = containerStatus,
      localVolumes = localVolumes,
      message = message,
      specReference = Some(s"/v2/pods${pod.id}::versions/${instance.runSpecVersion.toOffsetDateTime}"),
      lastUpdated = instance.state.since.toOffsetDateTime, // TODO(jdef) pods we don't actually track lastUpdated yet
      lastChanged = instance.state.since.toOffsetDateTime
    )
  }

  // TODO: Consider using a view here (since we flatMap and groupBy)
  def networkStatuses(tasks: Seq[task.Task]): Seq[NetworkStatus] = tasks.flatMap { task =>
    task.status.mesosStatus.filter(_.hasContainerStatus).fold(List.empty[NetworkStatus]) { mesosStatus =>
      mesosStatus.getContainerStatus.getNetworkInfosList.map { networkInfo =>
        NetworkStatus(
          name = if (networkInfo.hasName) Some(networkInfo.getName) else None,
          addresses = networkInfo.getIpAddressesList
            .withFilter(_.hasIpAddress).map(_.getIpAddress)(collection.breakOut)
        )
      }(collection.breakOut)
    }
  }.groupBy(_.name).values.map { toMerge =>
    val networkStatus: NetworkStatus = toMerge.reduceLeft { (merged, single) =>
      merged.copy(addresses = merged.addresses ++ single.addresses)
    }
    networkStatus.copy(addresses = networkStatus.addresses.distinct)
  }(collection.breakOut)

  def healthCheckEndpoint(spec: MesosContainer): Option[String] = {
    def invalidPortIndex[T](msg: String): T = throw new IllegalStateException(msg)
    spec.healthCheck.collect {
      case check: MesosHttpHealthCheck => check.portIndex
      case check: MesosTcpHealthCheck => check.portIndex
    }.map {
      _.fold(
        invalidPortIndex(s"missing portIndex to map to an endpoint for container ${spec.name}")
      ){
          case portName: PortReference.ByName => portName.value
          case _ => invalidPortIndex("index byInt not supported for pods")
        }
    }
  }

  /**
    * check that task is running; if so, calculate health condition according to possible command-line health check
    * or else endpoint health checks.
    */
  def maybeHealthCondition(
    status: task.Task.Status,
    maybeContainerSpec: Option[MesosContainer],
    endpointStatuses: Seq[ContainerEndpointStatus],
    since: OffsetDateTime,
    instance: Instance): Option[StatusCondition] = {

    status.condition match {
      // do not show health status for instances that are not expunged because of reservation but are terminal at the same time
      case c if c.isTerminal && instance.hasReservation => None
      case condition.Condition.Staging |
        condition.Condition.Starting |
        condition.Condition.Scheduled |
        condition.Condition.Provisioned =>

        // not useful to report health conditions for tasks that have never reached a running state
        None
      case _ =>
        val healthy: Option[(Boolean, String)] = maybeContainerSpec.flatMap { containerSpec =>
          val usingCommandHealthCheck: Boolean = containerSpec.healthCheck.exists {
            case _: MesosCommandHealthCheck => true
            case _ => false
          }
          if (usingCommandHealthCheck) {
            Some(status.healthy.fold(false -> HEALTH_UNREPORTED) {
              _ -> HEALTH_REPORTED
            })
          } else {
            val ep = healthCheckEndpoint(containerSpec)
            ep.map { endpointName =>
              val epHealthy: Option[Boolean] = endpointStatuses.find(_.name == endpointName).flatMap(_.healthy)
              // health check endpoint was specified, but if we don't have a value for health yet then generate a
              // meaningful reason code
              epHealthy.fold(false -> HEALTH_UNREPORTED) {
                _ -> HEALTH_REPORTED
              }
            }
          }
        }
        healthy.map { h =>
          StatusCondition(
            name = STATUS_CONDITION_HEALTHY,
            lastChanged = since,
            lastUpdated = since, // TODO(jdef) pods only changes are propagated, so this isn't right
            value = h._1.toString,
            reason = Some(h._2)
          )
        }
    }
  }

  def endpointStatuses(
    pod: PodDefinition,
    maybeContainerSpec: Option[MesosContainer],
    task: core.task.Task): Seq[ContainerEndpointStatus] =

    maybeContainerSpec.flatMap { _ =>
      if (task.isActive) {
        val taskHealthy: Option[Boolean] = // only calculate this once so we do it here
          task.status.healthy

        task.taskId.containerName.flatMap { containerName =>
          pod.container(containerName).flatMap { containerSpec =>
            val endpointRequestedHostPort: Seq[String] =
              containerSpec.endpoints.withFilter(_.hostPort.isDefined).map(_.name)
            val reservedHostPorts: Seq[Int] = task.status.networkInfo.hostPorts

            // TODO(jdef): This assumption doesn't work...
            /*assume(
                endpointRequestedHostPort.size == reservedHostPorts.size,
                s"number of reserved host ports ${reservedHostPorts.size} should equal number of" +
                  s"requested host ports ${endpointRequestedHostPort.size}")
              */
            // we assume that order has been preserved between the allocated port list and the endpoint list
            // TODO(jdef) pods what actually guarantees that this doesn't change? (do we check this upon pod update?)
            def reservedEndpointStatus: Seq[ContainerEndpointStatus] =
              endpointRequestedHostPort.zip(reservedHostPorts).map {
                case (name, allocated) =>
                  ContainerEndpointStatus(name, Some(allocated))
              }

            def unreservedEndpointStatus: Seq[ContainerEndpointStatus] = containerSpec.endpoints
              .withFilter(_.hostPort.isEmpty).map(ep => ContainerEndpointStatus(ep.name))

            def withHealth: Seq[ContainerEndpointStatus] = {
              val allEndpoints = reservedEndpointStatus ++ unreservedEndpointStatus

              // check whether health checks are enabled for this endpoint. if they are then propagate the mesos task
              // health check result.
              healthCheckEndpoint(containerSpec).flatMap { name =>
                // update the `health` field of the endpoint status...
                allEndpoints.find(_.name == name).map(_.copy(healthy = taskHealthy))
              }.fold(allEndpoints) { updated =>
                // ... and replace the old entry with the one from above
                allEndpoints.filter(_.name != updated.name) ++ List(updated)
              }
            }
            Some(withHealth)
          }
        }

      } else {
        None
      }
    }.getOrElse(List.empty[ContainerEndpointStatus])

  def podInstanceState(
    instanceCondition: core.condition.Condition,
    containerStatus: Seq[ContainerStatus]): (PodInstanceState, Option[String]) = {

    instanceCondition match {
      case condition.Condition.Scheduled |
        condition.Condition.Provisioned |
        condition.Condition.Reserved =>
        PodInstanceState.Pending -> None
      case condition.Condition.Staging |
        condition.Condition.Starting =>
        PodInstanceState.Staging -> None
      case condition.Condition.Error |
        condition.Condition.Failed |
        condition.Condition.Finished |
        condition.Condition.Killed |
        condition.Condition.Gone |
        condition.Condition.Dropped |
        condition.Condition.Unknown |
        condition.Condition.Killing =>
        PodInstanceState.Terminal -> None
      case condition.Condition.Unreachable |
        condition.Condition.UnreachableInactive =>
        PodInstanceState.Degraded -> Some(MSG_INSTANCE_UNREACHABLE)
      case condition.Condition.Running =>
        if (containerStatus.exists(_.conditions.exists { cond =>
          cond.name == STATUS_CONDITION_HEALTHY && cond.value == "false"
        }))
          PodInstanceState.Degraded -> Some(MSG_INSTANCE_UNHEALTHY_CONTAINERS)
        else
          PodInstanceState.Stable -> None
    }
  }
}

object PodStatusConversion extends PodStatusConversion {

  val HEALTH_UNREPORTED = "health-unreported-by-mesos"
  val HEALTH_REPORTED = "health-reported-by-mesos"

  val STATUS_CONDITION_HEALTHY = "healthy"

  val MSG_INSTANCE_UNREACHABLE = "pod instance has become unreachable"
  val MSG_INSTANCE_UNHEALTHY_CONTAINERS = "at least one container is not healthy"
}
