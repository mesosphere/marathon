package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.Protos
import mesosphere.marathon.core.pod.{ PodDefinition, Network }
import mesosphere.marathon.raml.{ Constraint => RAMLConstraint, UpgradeStrategy => _, _ }
import mesosphere.marathon.state.{ BackoffStrategy, PathId, Secret, UpgradeStrategy, Timestamp }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

trait PodConversion {

  //scalastyle:off
  implicit val fromAPIObjectToPod: Converter[Pod,PodDefinition] = Converter { podDef: Pod =>

    import PodDefinition.{
      DefaultInstances, DefaultMaxInstances, DefaultUpgradeStrategy, DefaultBackoffStrategy, DefaultEnv }

    val constraints = podDef.scheduling.flatMap(_.placement).map(_.constraints.map { c =>
      val operator = c.operator match {
        case ConstraintOperator.Unique => Protos.Constraint.Operator.UNIQUE
        case ConstraintOperator.Cluster => Protos.Constraint.Operator.CLUSTER
        case ConstraintOperator.GroupBy => Protos.Constraint.Operator.GROUP_BY
        case ConstraintOperator.Like => Protos.Constraint.Operator.LIKE
        case ConstraintOperator.Unlike => Protos.Constraint.Operator.UNLIKE
        case ConstraintOperator.MaxPer => Protos.Constraint.Operator.MAX_PER
      }

      val builder = Protos.Constraint.newBuilder().setField(c.fieldName).setOperator(operator)
      c.value.foreach(builder.setValue)
      builder.build()
    }.toSet).getOrElse(Set.empty)

    val (instances, maxInstances) = podDef.scaling.fold(DefaultInstances -> DefaultMaxInstances) {
      case FixedPodScalingPolicy(i, m) => i -> m
    }

    val networks: Seq[Network] = podDef.networks.map(Converter(_))

    val resourceRoles = podDef.scheduling.flatMap(_.placement).fold(Set.empty[String])(_.acceptedResourceRoles.toSet)

    val upgradeStrategy = podDef.scheduling.flatMap(_.upgrade).fold(DefaultUpgradeStrategy) { raml =>
      UpgradeStrategy(raml.minimumHealthCapacity, raml.maximumOverCapacity)
    }

    val backoffStrategy = podDef.scheduling.flatMap { policy =>
      policy.backoff.map { strategy =>
        BackoffStrategy(strategy.backoff.seconds, strategy.maxLaunchDelay.seconds, strategy.backoffFactor)
      }
    }.getOrElse(DefaultBackoffStrategy)

    new PodDefinition(
      id = PathId(podDef.id).canonicalPath(),
      user = podDef.user,
      env = podDef.environment.fold(DefaultEnv)(Converter(_)),
      labels = podDef.labels.fold(Map.empty[String, String])(_.values),
      acceptedResourceRoles = resourceRoles,
      secrets = podDef.secrets.fold(Map.empty[String, Secret])(_.values.mapValues(s => Secret(s.source))),
      containers = podDef.containers.map(Converter(_)),
      instances = instances,
      maxInstances = maxInstances,
      constraints = constraints,
      version = podDef.version.fold(Timestamp.now())(Timestamp(_)),
      podVolumes = podDef.volumes,
      networks = networks,
      backoffStrategy = backoffStrategy,
      upgradeStrategy = upgradeStrategy
    )
  }
  //scalastyle:on

  implicit val fromPodToAPIObject: Converter[PodDefinition,Pod] = Converter { pod: PodDefinition =>

    val constraintDefs: Seq[RAMLConstraint] = pod.constraints.map { c =>
      val operator = c.getOperator match {
        case Protos.Constraint.Operator.UNIQUE => ConstraintOperator.Unique
        case Protos.Constraint.Operator.CLUSTER => ConstraintOperator.Cluster
        case Protos.Constraint.Operator.GROUP_BY => ConstraintOperator.GroupBy
        case Protos.Constraint.Operator.LIKE => ConstraintOperator.Like
        case Protos.Constraint.Operator.UNLIKE => ConstraintOperator.Unlike
        case Protos.Constraint.Operator.MAX_PER => ConstraintOperator.MaxPer
      }
      RAMLConstraint(c.getField, operator, Option(c.getValue))
    }(collection.breakOut)

    val ramlUpgradeStrategy = PodUpgradeStrategy(
      pod.upgradeStrategy.minimumHealthCapacity,
      pod.upgradeStrategy.maximumOverCapacity)

    val ramlBackoffStrategy = PodSchedulingBackoffStrategy(
      backoff = pod.backoffStrategy.backoff.toMillis.toDouble / 1000.0,
      maxLaunchDelay = pod.backoffStrategy.maxLaunchDelay.toMillis.toDouble / 1000.0,
      backoffFactor = pod.backoffStrategy.factor)
    val schedulingPolicy = PodSchedulingPolicy(Some(ramlBackoffStrategy), Some(ramlUpgradeStrategy),
      Some(PodPlacementPolicy(constraintDefs, pod.acceptedResourceRoles.toVector)))

    val scalingPolicy = FixedPodScalingPolicy(pod.instances, pod.maxInstances)

    Pod(
      id = pod.id.toString,
      version = Some(pod.version.toOffsetDateTime),
      user = pod.user,
      containers = pod.containers.map(Converter(_)),
      environment = if(pod.env.isEmpty) None else Some(Converter(pod.env)),
      labels = Some(KVLabels(pod.labels)),
      scaling = Some(scalingPolicy),
      scheduling = Some(schedulingPolicy),
      volumes = pod.podVolumes,
      networks = pod.networks.map(Converter(_))
    )
  }
}
