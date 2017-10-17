package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.pod.PodDefinition._
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.concurrent.duration._

trait PodConversion extends NetworkConversion with ConstraintConversion with ContainerConversion with EnvVarConversion
    with SecretConversion with UnreachableStrategyConversion with KillSelectionConversion {

  implicit val podRamlReader: Reads[Pod, PodDefinition] = Reads { podd =>
    val instances = podd.scaling.fold(DefaultInstances) {
      case FixedPodScalingPolicy(i) => i
    }

    val networks: Seq[pod.Network] = podd.networks.map(Raml.fromRaml[Network, pod.Network])

    val resourceRoles = podd.scheduling.flatMap(_.placement)
      .fold(Set.empty[String])(_.acceptedResourceRoles.toSet)

    val upgradeStrategy = podd.scheduling.flatMap(_.upgrade).fold(DefaultUpgradeStrategy) { raml =>
      state.UpgradeStrategy(raml.minimumHealthCapacity, raml.maximumOverCapacity)
    }

    val unreachableStrategy = podd.scheduling.flatMap(_.unreachableStrategy).fold(DefaultUnreachableStrategy)(Raml.fromRaml(_))
    val killSelection: state.KillSelection = podd.scheduling.fold(state.KillSelection.DefaultKillSelection) {
      _.killSelection.fold(state.KillSelection.DefaultKillSelection)(Raml.fromRaml(_))
    }

    val backoffStrategy = podd.scheduling.flatMap { policy =>
      policy.backoff.map { strategy =>
        state.BackoffStrategy(strategy.backoff.seconds, strategy.maxLaunchDelay.seconds, strategy.backoffFactor)
      }
    }.getOrElse(DefaultBackoffStrategy)

    val constraints: Set[Protos.Constraint] =
      podd.scheduling.flatMap(_.placement.map(_.constraints.map(Raml.fromRaml(_))))
        .getOrElse(Set.empty[Protos.Constraint])

    val executorResources: ExecutorResources = podd.executorResources.getOrElse(PodDefinition.DefaultExecutorResources.toRaml)

    new PodDefinition(
      id = PathId(podd.id).canonicalPath(),
      user = podd.user,
      env = Raml.fromRaml(podd.environment),
      labels = podd.labels,
      acceptedResourceRoles = resourceRoles,
      secrets = Raml.fromRaml(podd.secrets),
      containers = podd.containers.map(Raml.fromRaml(_)),
      instances = instances,
      constraints = constraints,
      version = podd.version.fold(Timestamp.now())(Timestamp(_)),
      podVolumes = podd.volumes.map(Raml.fromRaml(_)),
      networks = networks,
      backoffStrategy = backoffStrategy,
      upgradeStrategy = upgradeStrategy,
      executorResources = executorResources.fromRaml,
      unreachableStrategy = unreachableStrategy,
      killSelection = killSelection
    )
  }

  implicit val podRamlWriter: Writes[PodDefinition, Pod] = Writes { podDef =>

    val ramlUpgradeStrategy = PodUpgradeStrategy(
      podDef.upgradeStrategy.minimumHealthCapacity,
      podDef.upgradeStrategy.maximumOverCapacity)

    val ramlBackoffStrategy = PodSchedulingBackoffStrategy(
      backoff = podDef.backoffStrategy.backoff.toMillis.toDouble / 1000.0,
      maxLaunchDelay = podDef.backoffStrategy.maxLaunchDelay.toMillis.toDouble / 1000.0,
      backoffFactor = podDef.backoffStrategy.factor)
    val schedulingPolicy = PodSchedulingPolicy(
      Some(ramlBackoffStrategy),
      Some(ramlUpgradeStrategy),
      Some(PodPlacementPolicy(
        podDef.constraints.toRaml[Set[Constraint]],
        podDef.acceptedResourceRoles.toIndexedSeq)),
      Some(podDef.killSelection.toRaml),
      Some(podDef.unreachableStrategy.toRaml)
    )

    val scalingPolicy = FixedPodScalingPolicy(podDef.instances)

    Pod(
      id = podDef.id.toString,
      version = Some(podDef.version.toOffsetDateTime),
      user = podDef.user,
      containers = podDef.containers.map(Raml.toRaml(_)),
      environment = Raml.toRaml(podDef.env),
      labels = podDef.labels,
      scaling = Some(scalingPolicy),
      secrets = Raml.toRaml(podDef.secrets),
      scheduling = Some(schedulingPolicy),
      volumes = podDef.podVolumes.map(Raml.toRaml(_)),
      networks = podDef.networks.map(Raml.toRaml(_)),
      executorResources = Some(podDef.executorResources.toRaml)
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
