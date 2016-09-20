package mesosphere.marathon.raml

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.RunSpec

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

trait PodStatusConversion {

  import PodStatusConversion._

  /**
    * generate a pod instance status RAML for some instance.
    * @throws IllegalArgumentException if you provide a non-pod `spec`
    */
  implicit val podInstanceStatusRamlWriter: Writes[Source,PodInstanceStatus] = Writes[Source,PodInstanceStatus] { src =>

    val (spec,instance) = src

    // BLOCKED: need capability to get the container name from Task somehow, current thinking is that Task.Id will
    // provide such an API.

    val pod: PodDefinition = spec match {
      case x: PodDefinition => x
      case _ => throw new IllegalArgumentException(s"expected a pod spec instead of $spec")
    }

    assume(
      pod.id == instance.instanceId.runSpecId,
      s"pod id ${pod.id} should match spec id of the instance ${instance.instanceId.runSpecId}")

    // TODO(jdef) associate task w/ container by name, allocated host ports should be in relative order
    // def endpoints = instance.tasks.map(_.launched.map(_.hostPorts))

    def containerStatus: Seq[ ContainerStatus ] = instance.tasks.map { task =>
      val since = task.status.startedAt.getOrElse(task.status.stagedAt).toOffsetDateTime // TODO(jdef) inaccurate

      // some other layer should provide termination history
      // TODO(jdef) message, conditions
      ContainerStatus(
        name = task.taskId.mesosTaskId.getValue, //TODO(jdef) pods this is wrong, should be the container name from spec
        status = task.status.taskStatus.toMesosStateName,
        statusSince = since,
        containerId = task.launchedMesosId.map(_.getValue),
        endpoints = Seq.empty, //TODO(jdef) pods, report endpoint health, allocated host ports here
        lastUpdated = since, // TODO(jdef) pods fixme
        lastChanged = since // TODO(jdef) pods.fixme
      )
    }(collection.breakOut)

    val derivedStatus: PodInstanceState = instance.state.status match {
      case InstanceStatus.Created | InstanceStatus.Reserved => PodInstanceState.Pending
      case InstanceStatus.Staging | InstanceStatus.Starting => PodInstanceState.Staging
      case InstanceStatus.Error | InstanceStatus.Failed | InstanceStatus.Finished | InstanceStatus.Killed |
           InstanceStatus.Gone | InstanceStatus.Dropped | InstanceStatus.Unknown | InstanceStatus.Killing |
           InstanceStatus.Unreachable => PodInstanceState.Terminal
      case InstanceStatus.Running =>
        if (instance.state.healthy.getOrElse(true)) PodInstanceState.Stable else PodInstanceState.Degraded
    }

    val networkStatus: Seq[ NetworkStatus ] = instance.tasks.flatMap { task =>
      task.mesosStatus.filter(_.hasContainerStatus).fold(Seq.empty[ NetworkStatus ]) { mesosStatus =>
        mesosStatus.getContainerStatus.getNetworkInfosList.asScala.map { networkInfo =>
          NetworkStatus(
            name = if (networkInfo.hasName) Some(networkInfo.getName) else None,
            addresses = networkInfo.getIpAddressesList.asScala
              .filter(_.hasIpAddress).map(_.getIpAddress)(collection.breakOut)
          )
        }(collection.breakOut)
      }.groupBy(_.name).values.map { toMerge =>
        val networkStatus: NetworkStatus = toMerge.reduceLeft { (merged, single) =>
          merged.copy(addresses = merged.addresses ++ single.addresses)
        }
        networkStatus.copy(addresses = networkStatus.addresses.distinct)
      }
    }(collection.breakOut)

    val resources: Option[ Resources ] = instance.state.status match {
      case InstanceStatus.Staging | InstanceStatus.Starting | InstanceStatus.Running =>
        val containerResources = pod.containers.map(_.resources).fold(Resources(0, 0, 0, 0)) { (acc, res) =>
          acc.copy(
            cpus = acc.cpus + res.cpus,
            mem = acc.mem + res.mem,
            disk = acc.disk + res.disk,
            gpus = acc.gpus + res.gpus
          )
        }
        Some(containerResources.copy(
          cpus = containerResources.cpus + PodDefinition.DefaultExecutorCpus,
          mem = containerResources.mem + PodDefinition.DefaultExecutorMem
          // TODO(jdef) pods account for executor disk space, see TaskGroupBuilder for reference
        ))
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

object PodStatusConversion extends PodStatusConversion {

  type Source = (RunSpec, Instance)
}
