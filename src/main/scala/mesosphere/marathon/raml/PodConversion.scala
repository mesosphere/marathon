package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.pod.PodDefinition._
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.concurrent.duration._

trait PodConversion extends NetworkConversion with ConstraintConversion with ContainerConversion with EnvVarConversion
    with SecretConversion with UnreachableStrategyConversion with KillSelectionConversion {

  implicit val podRamlReader: Reads[Pod, PodDefinition] = Reads { podDef =>
    val instances = podDef.scaling.fold(DefaultInstances) {
      case FixedPodScalingPolicy(i) => i
    }

    val networks: Seq[pod.Network] = podDef.networks.map(Raml.fromRaml[Network, pod.Network])

    val resourceRoles = podDef.scheduling.flatMap(_.placement)
      .fold(Set.empty[String])(_.acceptedResourceRoles.toSet)

    val upgradeStrategy = podDef.scheduling.flatMap(_.upgrade).fold(DefaultUpgradeStrategy) { raml =>
      state.UpgradeStrategy(raml.minimumHealthCapacity, raml.maximumOverCapacity)
    }

    val unreachableStrategy = podDef.scheduling.flatMap(_.unreachableStrategy).fold(DefaultUnreachableStrategy)(Raml.fromRaml(_))
    val killSelection: state.KillSelection = podDef.scheduling.fold(state.KillSelection.DefaultKillSelection) {
      _.killSelection.fold(state.KillSelection.DefaultKillSelection)(Raml.fromRaml(_))
    }

    val backoffStrategy = podDef.scheduling.flatMap { policy =>
      policy.backoff.map { strategy =>
        state.BackoffStrategy(strategy.backoff.seconds, strategy.maxLaunchDelay.seconds, strategy.backoffFactor)
      }
    }.getOrElse(DefaultBackoffStrategy)

    val constraints: Set[Protos.Constraint] =
      podDef.scheduling.flatMap(_.placement.map(_.constraints.map(Raml.fromRaml(_))))
        .getOrElse(Set.empty[Protos.Constraint])

    val executorResources: ExecutorResources = podDef.executorResources.getOrElse(PodDefinition.DefaultExecutorResources.toRaml)

    new PodDefinition(
      id = PathId(podDef.id).canonicalPath(),
      user = podDef.user,
      env = Raml.fromRaml(podDef.environment),
      labels = podDef.labels,
      acceptedResourceRoles = resourceRoles,
      secrets = Raml.fromRaml(podDef.secrets),
      containers = podDef.containers.map(Raml.fromRaml(_)),
      instances = instances,
      constraints = constraints,
      versionInfo = state.VersionInfo.OnlyVersion(podDef.version.fold(Timestamp.now())(Timestamp(_))),
      podVolumes = podDef.volumes.map(Raml.fromRaml(_)),
      networks = networks,
      backoffStrategy = backoffStrategy,
      upgradeStrategy = upgradeStrategy,
      executorResources = executorResources.fromRaml,
      unreachableStrategy = unreachableStrategy,
      killSelection = killSelection
    )
  }

  implicit val podRamlWriter: Writes[PodDefinition, Pod] = Writes { pod =>

    val ramlUpgradeStrategy = PodUpgradeStrategy(
      pod.upgradeStrategy.minimumHealthCapacity,
      pod.upgradeStrategy.maximumOverCapacity)

    val ramlBackoffStrategy = PodSchedulingBackoffStrategy(
      backoff = pod.backoffStrategy.backoff.toMillis.toDouble / 1000.0,
      maxLaunchDelay = pod.backoffStrategy.maxLaunchDelay.toMillis.toDouble / 1000.0,
      backoffFactor = pod.backoffStrategy.factor)
    val schedulingPolicy = PodSchedulingPolicy(
      Some(ramlBackoffStrategy),
      Some(ramlUpgradeStrategy),
      Some(PodPlacementPolicy(
        pod.constraints.toRaml[Set[Constraint]],
        pod.acceptedResourceRoles.toIndexedSeq)),
      Some(pod.killSelection.toRaml),
      Some(pod.unreachableStrategy.toRaml)
    )

    val scalingPolicy = FixedPodScalingPolicy(pod.instances)

    Pod(
      id = pod.id.toString,
      version = Some(pod.version.toOffsetDateTime),
      user = pod.user,
      containers = pod.containers.map(Raml.toRaml(_)),
      environment = Raml.toRaml(pod.env),
      labels = pod.labels,
      scaling = Some(scalingPolicy),
      secrets = Raml.toRaml(pod.secrets),
      scheduling = Some(schedulingPolicy),
      volumes = pod.podVolumes.map(Raml.toRaml(_)),
      networks = pod.networks.map(Raml.toRaml(_)),
      executorResources = Some(pod.executorResources.toRaml)
    )
  }

  implicit val resourcesReads: Reads[ExecutorResources, Resources] = Reads { executorResources =>
    Resources(
      cpus = executorResources.cpus,
      mem = executorResources.mem,
      disk = executorResources.disk
    )
  }

  implicit val executorResourcesWrites: Writes[Resources, ExecutorResources] = Writes { resources =>
    ExecutorResources(
      cpus = resources.cpus,
      mem = resources.mem,
      disk = resources.disk
    )
  }
}

object PodConversion extends PodConversion
