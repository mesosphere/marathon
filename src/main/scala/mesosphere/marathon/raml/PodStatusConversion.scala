package mesosphere.marathon
package raml

import java.time.OffsetDateTime

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.{ MesosCommandHealthCheck, MesosHttpHealthCheck, MesosTcpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.stream.Implicits._

trait PodStatusConversion {

  import PodStatusConversion._

  implicit val taskToContainerStatus: Writes[(PodDefinition, Task), ContainerStatus] = Writes { src =>
    val (pod, task) = src
    val since = task.status.startedAt.getOrElse(task.status.stagedAt).toOffsetDateTime // TODO(jdef) inaccurate

    val maybeContainerSpec: Option[MesosContainer] = pod.container(task.taskId)

    // possible that a new pod spec might not have a container with a name that was used in an old pod spec?
    val endpointStatus = endpointStatuses(pod, maybeContainerSpec, task)

    // some other layer should provide termination history

    // if, for some very strange reason, we cannot determine the container name from the task ID then default to
    // the Mesos task ID itself
    val displayName = task.taskId.containerName.getOrElse(task.taskId.mesosTaskId.getValue)

    val resources: Option[Resources] = {
      import Condition._
      task.status.condition match {
        case Staging | Starting | Running | Reserved | Unreachable | Killing =>
          maybeContainerSpec.map(_.resources)
        case _ =>
          None
      }
    }

    // TODO(jdef) message
    ContainerStatus(
      name = displayName,
      status = Condition.toMesosTaskStateOrStaging(task.status.condition).toString,
      statusSince = since,
      containerId = task.launchedMesosId.map(_.getValue),
      endpoints = endpointStatus,
      conditions = Seq(maybeHealthCondition(task.status, maybeContainerSpec, endpointStatus, since)).flatten,
      resources = resources,
      lastUpdated = since, // TODO(jdef) pods fixme
      lastChanged = since // TODO(jdef) pods.fixme
    )
  }

  /**
    * generate a pod instance status RAML for some instance.
    */
  implicit val podInstanceStatusRamlWriter: Writes[(PodDefinition, Instance), PodInstanceStatus] = Writes { src =>

    val (pod, instance) = src

    assume(
      pod.id == instance.instanceId.runSpecId,
      s"pod id ${pod.id} should match spec id of the instance ${instance.instanceId.runSpecId}")

    val containerStatus: Seq[ContainerStatus] = instance.tasksMap.values.map(t => Raml.toRaml((pod, t)))(collection.breakOut)
    val (derivedStatus: PodInstanceState, message: Option[String]) = podInstanceState(
      instance.state.condition, containerStatus)

    val networkStatus: Seq[NetworkStatus] = networkStatuses(instance.tasksMap.values.to[Seq])
    val resources: Resources = containerStatus.flatMap(_.resources).foldLeft(PodDefinition.DefaultExecutorResources) { (all, res) =>
      all.copy(cpus = all.cpus + res.cpus, mem = all.mem + res.mem, disk = all.disk + res.disk, gpus = all.gpus + res.gpus)
    }

    // TODO(jdef) message, conditions: for example it would probably be nice to see a "healthy" condition here that
    // summarizes the conditions of the same name for each of the instance's containers.
    PodInstanceStatus(
      id = instance.instanceId.idString,
      status = derivedStatus,
      statusSince = instance.state.since.toOffsetDateTime,
      agentId = instance.agentInfo.agentId,
      agentHostname = Some(instance.agentInfo.host),
      resources = Some(resources),
      networks = networkStatus,
      containers = containerStatus,
      message = message,
      specReference = Some(s"/v2/pods${pod.id}::versions/${instance.runSpecVersion.toOffsetDateTime}"),
      lastUpdated = instance.state.since.toOffsetDateTime, // TODO(jdef) pods we don't actually track lastUpdated yet
      lastChanged = instance.state.since.toOffsetDateTime
    )
  }

  // TODO: Consider using a view here (since we flatMap and groupBy)
  def networkStatuses(tasks: Seq[Task]): Seq[NetworkStatus] = tasks.flatMap { task =>
    task.status.mesosStatus.filter(_.hasContainerStatus).fold(Seq.empty[NetworkStatus]) { mesosStatus =>
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
    status: Task.Status,
    maybeContainerSpec: Option[MesosContainer],
    endpointStatuses: Seq[ContainerEndpointStatus],
    since: OffsetDateTime): Option[StatusCondition] =

    status.condition match {
      case Condition.Created | Condition.Staging | Condition.Starting | Condition.Reserved =>
        // not useful to report health conditions for tasks that have never reached a running state
        None
      case _ =>
        val healthy: Option[(Boolean, String)] = maybeContainerSpec.flatMap { containerSpec =>
          val usingCommandHealthCheck: Boolean = containerSpec.healthCheck.exists {
            case _: MesosCommandHealthCheck => true
            case _ => false
          }
          if (usingCommandHealthCheck) {
            Some(status.healthy.fold(false -> HEALTH_UNREPORTED) { _ -> HEALTH_REPORTED })
          } else {
            val ep = healthCheckEndpoint(containerSpec)
            ep.map { endpointName =>
              val epHealthy: Option[Boolean] = endpointStatuses.find(_.name == endpointName).flatMap(_.healthy)
              // health check endpoint was specified, but if we don't have a value for health yet then generate a
              // meaningful reason code
              epHealthy.fold(false -> HEALTH_UNREPORTED) { _ -> HEALTH_REPORTED }
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

  def endpointStatuses(
    pod: PodDefinition,
    maybeContainerSpec: Option[MesosContainer],
    task: Task): Seq[ContainerEndpointStatus] =

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
                allEndpoints.filter(_.name != updated.name) ++ Seq(updated)
              }
            }
            Some(withHealth)
          }
        }

      } else {
        None
      }
    }.getOrElse(Seq.empty[ContainerEndpointStatus])

  def podInstanceState(
    condition: Condition,
    containerStatus: Seq[ContainerStatus]): (PodInstanceState, Option[String]) = {

    import Condition._

    condition match {
      case Created | Reserved =>
        PodInstanceState.Pending -> None
      case Staging | Starting =>
        PodInstanceState.Staging -> None
      case Condition.Error | Failed | Finished | Killed | Gone | Dropped | Unknown | Killing =>
        PodInstanceState.Terminal -> None
      case Unreachable | UnreachableInactive =>
        PodInstanceState.Degraded -> Some(MSG_INSTANCE_UNREACHABLE)
      case Running =>
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
