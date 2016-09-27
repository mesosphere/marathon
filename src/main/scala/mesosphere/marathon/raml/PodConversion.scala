package mesosphere.marathon.raml

import mesosphere.marathon.core.pod
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.pod.PodDefinition._
import mesosphere.marathon.{ Protos, state }
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

trait PodConversion extends NetworkConversion with ConstraintConversion
    with ContainerConversion with EnvVarConversion with SecretConversion {
  implicit val podRamlReader: Reads[Pod, PodDefinition] = Reads { podDef =>
    val (instances, maxInstances) = podDef.scaling.fold(DefaultInstances -> DefaultMaxInstances) {
      case FixedPodScalingPolicy(i, m) => i -> m
    }

    val networks: Seq[pod.Network] = podDef.networks.map(Raml.fromRaml[Network, pod.Network])

    val resourceRoles = podDef.scheduling.flatMap(_.placement)
      .fold(Set.empty[String])(_.acceptedResourceRoles.toSet)

    val upgradeStrategy = podDef.scheduling.flatMap(_.upgrade).fold(DefaultUpgradeStrategy) { raml =>
      state.UpgradeStrategy(raml.minimumHealthCapacity, raml.maximumOverCapacity)
    }

    val backoffStrategy = podDef.scheduling.flatMap { policy =>
      policy.backoff.map { strategy =>
        state.BackoffStrategy(strategy.backoff.seconds, strategy.maxLaunchDelay.seconds, strategy.backoffFactor)
      }
    }.getOrElse(DefaultBackoffStrategy)

    val constraints: Set[Protos.Constraint] =
      podDef.scheduling.flatMap(_.placement.map(_.constraints.map(Raml.fromRaml(_)).toSet))
        .getOrElse(Set.empty[Protos.Constraint])

    new PodDefinition(
      id = PathId(podDef.id).canonicalPath(),
      user = podDef.user,
      env = Raml.fromRaml(podDef.environment),
      labels = podDef.labels,
      acceptedResourceRoles = resourceRoles,
      secrets = Raml.fromRaml(podDef.secrets),
      containers = podDef.containers.map(Raml.fromRaml(_)),
      instances = instances,
      maxInstances = maxInstances,
      constraints = constraints,
      version = podDef.version.fold(Timestamp.now())(Timestamp(_)),
      podVolumes = podDef.volumes.map(Raml.fromRaml(_)),
      networks = networks,
      backoffStrategy = backoffStrategy,
      upgradeStrategy = upgradeStrategy
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
    val schedulingPolicy = PodSchedulingPolicy(Some(ramlBackoffStrategy), Some(ramlUpgradeStrategy),
      Some(PodPlacementPolicy(
        pod.constraints.map(Raml.toRaml(_))(collection.breakOut),
        pod.acceptedResourceRoles.toVector)))

    val scalingPolicy = FixedPodScalingPolicy(pod.instances, pod.maxInstances)

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
      networks = pod.networks.map(Raml.toRaml(_))
    )
  }
}

object PodConversion extends PodConversion
