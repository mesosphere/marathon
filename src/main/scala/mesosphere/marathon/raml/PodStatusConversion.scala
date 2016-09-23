package mesosphere.marathon.raml

import java.time.OffsetDateTime

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.RunSpec

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

trait PodStatusConversion {

  import PodStatusConversion._

  implicit val taskToContainerStatus: Writes[(PodDefinition, Task), ContainerStatus] = Writes { src =>
    val (pod, task) = src
    val since = task.status.startedAt.getOrElse(task.status.stagedAt).toOffsetDateTime // TODO(jdef) inaccurate

    val maybeContainerName: Option[String] = task.taskId.containerName
    assume(maybeContainerName.nonEmpty, s"task id ${task.taskId} does not have a valid container name")

    val maybeContainerSpec: Option[MesosContainer] = maybeContainerName.flatMap { containerName =>
      pod.containers.find(_.name == containerName)
    }

    // possible that a new pod spec might not have a container with a name that was used in an old pod spec?
    val endpointStatus = endpointStatuses(pod, maybeContainerSpec, task)

    // some other layer should provide termination history

    // if, for some very strange reason, we cannot determine the container name from the task ID then default to
    // the Mesos task ID itself
    val displayName = task.taskId.containerName.getOrElse(task.taskId.mesosTaskId.getValue)

    // TODO(jdef) message
    ContainerStatus(
      name = displayName,
      status = task.status.taskStatus.toMesosStateName,
      statusSince = since,
      containerId = task.launchedMesosId.map(_.getValue),
      endpoints = endpointStatus,
      conditions = Seq(maybeHealthCondition(task.status, maybeContainerSpec, endpointStatus, since)).flatten,
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

    val (derivedStatus: PodInstanceState, message: Option[String]) = podInstanceState(
      instance.state.status, containerStatus)

    val networkStatus: Seq[NetworkStatus] = networkStatuses(instance.tasks.toVector)

    val resources: Option[Resources] = instance.state.status match {
      case InstanceStatus.Staging | InstanceStatus.Starting | InstanceStatus.Running | InstanceStatus.Reserved |
           InstanceStatus.Unreachable =>

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
      message = message,
      lastUpdated = instance.state.since.toOffsetDateTime, // TODO(jdef) pods we don't actually track lastUpdated yet
      lastChanged = instance.state.since.toOffsetDateTime
    )
  }

  // TODO: Consider using a view here (since we flatMap and groupBy)
  def networkStatuses(tasks: Seq[Task]): Seq[NetworkStatus] = tasks.flatMap { task =>
    task.mesosStatus.filter(_.hasContainerStatus).fold(Seq.empty[NetworkStatus]) { mesosStatus =>
      mesosStatus.getContainerStatus.getNetworkInfosList.asScala.map { networkInfo =>
        NetworkStatus(
          name = if (networkInfo.hasName) Some(networkInfo.getName) else None,
          addresses = networkInfo.getIpAddressesList.asScala
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

  def healthCheckEndpoint(spec: MesosContainer): Option[String] = spec.healthCheck match {
    case Some(HealthCheck(Some(HttpHealthCheck(endpoint, _, _)), _, _, _, _, _, _, _)) => Some(endpoint)
    case Some(HealthCheck(_, Some(TcpHealthCheck(endpoint)), _, _, _, _, _, _)) => Some(endpoint)
    case _ => None // no health check endpoint for this spec; command line checks aren't wired to endpoints!
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

    status.taskStatus match {
      case InstanceStatus.Created | InstanceStatus.Staging | InstanceStatus.Starting | InstanceStatus.Reserved =>
        // not useful to report health conditions for tasks that have never reached a running state
        None
      case _ =>
        val healthy: Option[ (Boolean, String) ] = maybeContainerSpec.flatMap { containerSpec =>
          val usingCommandHealthCheck: Boolean = containerSpec.healthCheck.exists(_.command.nonEmpty)
          if (usingCommandHealthCheck) {
            Some(status.mesosStatus.withFilter(_.hasHealthy).map(_.getHealthy).fold(false -> HEALTH_UNREPORTED) { h =>
              h -> HEALTH_REPORTED
            })
          } else {
            val ep = healthCheckEndpoint(containerSpec)
            ep.map { endpointName =>
              val epHealthy: Option[ Boolean ] = endpointStatuses.find(_.name == endpointName).flatMap(_.healthy)
              // health check endpoint was specified, but if we don't have a value for health yet then generate a
              // meaningful reason code
              epHealthy.fold(false -> HEALTH_UNREPORTED) { h => h -> HEALTH_REPORTED }
            }
          }
        }
        healthy.map { h =>
          StatusCondition(
            name = STATUS_CONDITION_HEALTHY,
            lastChanged = since, // TODO(jdef) pods we should be checking for a real change against some history
            lastUpdated = since,
            value = h._1.toString,
            reason = Some(h._2)
          )
        }
    }

  def endpointStatuses(
    pod: PodDefinition,
    maybeContainerSpec: Option[MesosContainer],
    task: Task): Seq[ContainerEndpointStatus] =

    maybeContainerSpec.flatMap { containerSpec =>
      task.launched.flatMap { launched =>

        val taskHealthy: Option[Boolean] = // only calculate this once so we do it here
          launched.status.mesosStatus.withFilter(_.hasHealthy).map(_.getHealthy)

        task.taskId.containerName.flatMap { containerName =>
          pod.containers.find(_.name == containerName).flatMap { containerSpec =>
            val endpointRequestedHostPort: Seq[String] = containerSpec.endpoints.collect {
              case Endpoint(name, _, Some(_), _, _) => name
            }(collection.breakOut)

            val reservedHostPorts: Seq[Int] = launched.hostPorts

            assume(
              endpointRequestedHostPort.size == reservedHostPorts.size,
              s"number of reserved host ports ${reservedHostPorts.size} should equal number of" +
                s"requested host ports ${endpointRequestedHostPort.size}")

            // we assume that order has been preserved between the allocated port list and the endpoint list
            // TODO(jdef) pods what actually guarantees that this doesn't change? (do we check this upon pod update?)
            def reservedEndpointStatus: Map[String, ContainerEndpointStatus] =
              endpointRequestedHostPort.zip(reservedHostPorts).map { entry =>
                val (name, allocated) = entry
                name -> ContainerEndpointStatus(name, Some(allocated))
              }.toMap

            def unreservedEndpointStatus: Map[String, ContainerEndpointStatus] = containerSpec.endpoints
              .withFilter(_.hostPort.isEmpty).map { ep => ep.name -> ContainerEndpointStatus(ep.name) }.toMap

            def withHealth: Seq[ContainerEndpointStatus] = {
              val allEndpoints = reservedEndpointStatus ++ unreservedEndpointStatus
              def maybeHealthCheckEndpointName = healthCheckEndpoint(containerSpec)

              // check whether health checks are enabled for this endpoint. if they are then propagate the mesos task
              // health check result.
              val healthy: Option[ContainerEndpointStatus] = maybeHealthCheckEndpointName.flatMap { name =>
                allEndpoints.get(name).map(_.copy(healthy = taskHealthy))
              }
              healthy.fold(allEndpoints) { ep => allEndpoints ++ Map(ep.name -> ep) }.values.toVector
            }
            Some(withHealth)
          }
        }
      }
    }.getOrElse(Seq.empty[ContainerEndpointStatus])

    def podInstanceState(
     status: InstanceStatus,
     containerStatus: Seq[ContainerStatus]): (PodInstanceState, Option[String]) =
       status match {
         case InstanceStatus.Created | InstanceStatus.Reserved =>
           PodInstanceState.Pending -> None
         case InstanceStatus.Staging | InstanceStatus.Starting =>
           PodInstanceState.Staging -> None
         case InstanceStatus.Error | InstanceStatus.Failed | InstanceStatus.Finished | InstanceStatus.Killed |
              InstanceStatus.Gone | InstanceStatus.Dropped | InstanceStatus.Unknown | InstanceStatus.Killing =>
           PodInstanceState.Terminal -> None
         case InstanceStatus.Unreachable =>
           PodInstanceState.Degraded -> Some(MSG_INSTANCE_UNREACHABLE)
         case InstanceStatus.Running =>
           if (containerStatus.exists(_.conditions.exists { cond =>
             cond.name == STATUS_CONDITION_HEALTHY && cond.value == "false"
           }))
             PodInstanceState.Degraded -> Some(MSG_INSTANCE_UNHEALTHY_CONTAINERS)
           else
             PodInstanceState.Stable -> None
       }
}

object PodStatusConversion extends PodStatusConversion {

  val HEALTH_UNREPORTED = "health-unreported-by-mesos"
  val HEALTH_REPORTED = "health-reported-by-mesos"

  val STATUS_CONDITION_HEALTHY = "healthy"

  val MSG_INSTANCE_UNREACHABLE = "pod instance has become unreachable"
  val MSG_INSTANCE_UNHEALTHY_CONTAINERS = "at least one container is not healthy"
}
