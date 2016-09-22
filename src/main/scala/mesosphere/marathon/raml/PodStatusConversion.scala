package mesosphere.marathon.raml

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.RunSpec

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

trait PodStatusConversion {

  implicit val taskToContainerStatus: Writes[(PodDefinition,Task), ContainerStatus] = Writes { src =>
    val (pod, task) = src
    val since = task.status.startedAt.getOrElse(task.status.stagedAt).toOffsetDateTime // TODO(jdef) inaccurate

    val maybeContainerName: Option[ String ] = task.taskId.containerName
    assume(maybeContainerName.nonEmpty, s"task id ${task.taskId} does not have a valid container name")

    val maybeContainerSpec: Option[ MesosContainer ] = maybeContainerName.flatMap { containerName =>
      pod.containers.find(_.name == containerName)
    }

    // possible that a new pod spec might not have a container with a name that was used in an old pod spec?
    val endpointStatuses: Seq[ ContainerEndpointStatus ] = maybeContainerSpec.flatMap { containerSpec =>
      task.launched.flatMap { launched =>
        task.taskId.containerName.flatMap { containerName =>
          pod.containers.find(_.name == containerName).flatMap { containerSpec =>
            val endpointRequestedHostPort: Seq[ String ] = containerSpec.endpoints.collect {
              case Endpoint(name, _, Some(_), _, _) => name
            }(collection.breakOut)

            val reservedHostPorts: Seq[ Int ] = launched.hostPorts

            assume(
              endpointRequestedHostPort.size == reservedHostPorts.size,
              s"number of reserved host ports ${reservedHostPorts.size} should equal number of" +
                s"requested host ports ${endpointRequestedHostPort.size}")

            // we assume that order has been preserved between the allocated port list and the endpoint list
            // TODO(jdef) pods what actually guarantees that this doesn't change? (do we check this upon pod update?)
            val reservedEndpointStatus: Seq[ ContainerEndpointStatus ] =
            endpointRequestedHostPort.zip(reservedHostPorts).map { entry =>
              val (name, allocated) = entry
              val healthy: Option[ Boolean ] = launched.status.mesosStatus.withFilter(_.hasHealthy).map(_.getHealthy)
              ContainerEndpointStatus(name, Some(allocated), healthy)
            }

            val unreservedEndpointStatus: Seq[ ContainerEndpointStatus ] = containerSpec.endpoints
              .withFilter(_.hostPort.isEmpty).map { ep => ContainerEndpointStatus(ep.name) }

            Some(reservedEndpointStatus ++ unreservedEndpointStatus)
          }
        }
      }
    }.getOrElse(Seq.empty[ ContainerEndpointStatus ])

    // some other layer should provide termination history

    // if, for some very strange reason, we cannot determine the container name from the task ID then default to
    // the Mesos task ID itself
    val displayName = task.taskId.containerName.getOrElse(task.taskId.mesosTaskId.getValue)

    // TODO(jdef) message, conditions
    ContainerStatus(
      name = displayName,
      status = task.status.taskStatus.toMesosStateName,
      statusSince = since,
      containerId = task.launchedMesosId.map(_.getValue),
      endpoints = endpointStatuses,
      lastUpdated = since, // TODO(jdef) pods fixme
      lastChanged = since // TODO(jdef) pods.fixme
    )
  }

  /**
    * generate a pod instance status RAML for some instance.
    */
  @throws[IllegalArgumentException]("if you provide a non-pod `spec`")
  implicit val podInstanceStatusRamlWriter: Writes[(RunSpec, Instance), PodInstanceStatus] = Writes { src =>

    val (spec, instance) = src

    val pod: PodDefinition = spec match {
      case x: PodDefinition => x
      case _ => throw new IllegalArgumentException(s"expected a pod spec instead of $spec")
    }

    assume(
      pod.id == instance.instanceId.runSpecId,
      s"pod id ${pod.id} should match spec id of the instance ${instance.instanceId.runSpecId}")

    def containerStatus: Seq[ContainerStatus] = instance.tasks.map(t => Raml.toRaml((pod, t)))(collection.breakOut)

    val derivedStatus: PodInstanceState = instance.state.status match {
      case InstanceStatus.Created | InstanceStatus.Reserved => PodInstanceState.Pending
      case InstanceStatus.Staging | InstanceStatus.Starting => PodInstanceState.Staging
      case InstanceStatus.Error | InstanceStatus.Failed | InstanceStatus.Finished | InstanceStatus.Killed |
        InstanceStatus.Gone | InstanceStatus.Dropped | InstanceStatus.Unknown | InstanceStatus.Killing |
        InstanceStatus.Unreachable => PodInstanceState.Terminal
      case InstanceStatus.Running =>
        if (instance.state.healthy.getOrElse(true)) PodInstanceState.Stable else PodInstanceState.Degraded
    }

    // TODO: Consider using a view here (since we flatMap and groupBy)
    val networkStatus: Seq[NetworkStatus] = instance.tasks.flatMap { task =>
      task.mesosStatus.filter(_.hasContainerStatus).fold(Seq.empty[NetworkStatus]) { mesosStatus =>
        mesosStatus.getContainerStatus.getNetworkInfosList.asScala.map { networkInfo =>
          NetworkStatus(
            name = if (networkInfo.hasName) Some(networkInfo.getName) else None,
            addresses = networkInfo.getIpAddressesList.asScala
              .withFilter(_.hasIpAddress).map(_.getIpAddress)(collection.breakOut)
          )
        }(collection.breakOut)
      }.groupBy(_.name).values.map { toMerge =>
        val networkStatus: NetworkStatus = toMerge.reduceLeft { (merged, single) =>
          merged.copy(addresses = merged.addresses ++ single.addresses)
        }
        networkStatus.copy(addresses = networkStatus.addresses.distinct)
      }
    }(collection.breakOut)

    val resources: Option[Resources] = instance.state.status match {
      case InstanceStatus.Staging | InstanceStatus.Starting | InstanceStatus.Running =>
        // Resources are automatically freed from the Mesos container as tasks transition to a terminal state.
        // TODO(jdef) pods should filter here for the containers that are non-terminal
        Some(pod.aggregateResources())
      case _ => None
    }

    // TODO(jdef) message, conditions
    PodInstanceStatus(
      id = instance.instanceId.idString,
      status = derivedStatus,
      statusSince = instance.state.since.toOffsetDateTime,
      agentHostname = Some(instance.agentInfo.host),
      resources = resources,
      networks = networkStatus,
      containers = containerStatus,
      lastUpdated = instance.state.since.toOffsetDateTime, // TODO(jdef) pods we don't actually track lastUpdated yet
      lastChanged = instance.state.since.toOffsetDateTime
    )
  }
}

object PodStatusConversion extends PodStatusConversion
