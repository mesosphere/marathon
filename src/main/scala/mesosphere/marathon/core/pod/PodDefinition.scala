package mesosphere.marathon.core.pod
// scalastyle:off
import java.time.OffsetDateTime

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.raml.{ ConstraintOperator, EnvVar, Label, MesosContainer, Network, PodDef, PodSchedulingPlacementPolicy, PodSchedulingPolicy, Volume, Constraint => RamlConstraint }
import mesosphere.marathon.state.{ EnvVarSecretRef, EnvVarString, EnvVarValue, PathId, RunnableSpec, Secret }

import scala.collection.immutable.Seq
// scalastyle:on

/**
  * A definition for Pods.
  */
case class PodDefinition(
    id: PathId,
    user: Option[String],
    env: Map[String, EnvVarValue],
    labels: Map[String, String],
    acceptedResourceRoles: Option[Set[String]],
    secrets: Map[String, Secret],
    containers: Seq[MesosContainer],
    instances: Int,
    maxInstances: Option[Int],
    constraints: Set[Constraint],
    version: OffsetDateTime,
    volumes: Seq[Volume],
    networks: Seq[Network]
) extends RunnableSpec {
  lazy val cpus: Double = containers.map(_.resources.cpus.toDouble).sum
  lazy val mem: Double = containers.map(_.resources.mem.toDouble).sum
  lazy val disk: Double = containers.flatMap(_.resources.disk.map(_.toDouble)).sum
  lazy val gpus: Int = containers.flatMap(_.resources.gpus).sum

  lazy val asPodDef: PodDef = {
    val envVars: Seq[EnvVar] = env.map {
      case (k, v) =>
        v match {
          case EnvVarSecretRef(secret) =>
            EnvVar(k, secret = Some(secret))
          case EnvVarString(value) =>
            EnvVar(k, value = Some(value))
        }
    }(collection.breakOut)

    val constraintDefs: Seq[RamlConstraint] = constraints.map { c =>
      val operator = c.getOperator match {
        case Constraint.Operator.UNIQUE => ConstraintOperator.Unique
        case Constraint.Operator.CLUSTER => ConstraintOperator.Cluster
        case Constraint.Operator.GROUP_BY => ConstraintOperator.GroupBy
        case Constraint.Operator.LIKE => ConstraintOperator.Like
        case Constraint.Operator.UNLIKE => ConstraintOperator.Unlike
        case Constraint.Operator.MAX_PER => ConstraintOperator.MaxPer
      }
      RamlConstraint(c.getField, operator, Option(c.getValue))
    }(collection.breakOut)

    // TODO: we're missing stuff here
    val schedulingPolicy = PodSchedulingPolicy(None, None,
      Some(PodSchedulingPlacementPolicy(constraintDefs, acceptedResourceRoles.fold(Seq.empty[String])(_.toVector))))

    val scalingPolicy = PodScalingPolicy(Some(Fixed(instances, maxInstances)))

    PodDef(
      id = id.safePath,
      version = Some(version),
      user = user,
      containers = containers,
      environment = envVars,
      labels = labels.map { case (k, v) => Label(k, if (v.nonEmpty) Some(v) else None) }(collection.breakOut),
      scaling = Some(scalingPolicy),
      scheduling = Some(schedulingPolicy),
      volumes = volumes,
      networks = networks
    )
  }
}

object PodDefinition {
  def apply(podDef: PodDef): PodDefinition = {
    val env: Map[String, EnvVarValue] =
      podDef.environment.withFilter(e => e.value.isDefined || e.secret.isDefined).map { env =>
        if (env.secret.isDefined)
          env.name -> EnvVarSecretRef(env.secret.get)
        else if (env.value.isDefined)
          env.name -> EnvVarString(env.value.get)
        else
          throw new IllegalStateException("Not reached")
      }(collection.breakOut)

    val constraints = podDef.scheduling.flatMap(_.placement).map(_.constraints.map { c =>
      val operator = c.operator match {
        case ConstraintOperator.Unique => Constraint.Operator.UNIQUE
        case ConstraintOperator.Cluster => Constraint.Operator.CLUSTER
        case ConstraintOperator.GroupBy => Constraint.Operator.GROUP_BY
        case ConstraintOperator.Like => Constraint.Operator.LIKE
        case ConstraintOperator.Unlike => Constraint.Operator.UNLIKE
        case ConstraintOperator.MaxPer => Constraint.Operator.MAX_PER
      }
      val builder = Constraint.newBuilder().setField(c.fieldName).setOperator(operator)
      c.value.foreach(builder.setValue)
      builder.build()
    }.toSet).getOrElse(Set.empty)

    PodDefinition(
      id = PathId(podDef.id),
      user = podDef.user,
      env = env,
      labels = podDef.labels.map(l => l.name -> l.value.getOrElse(""))(collection.breakOut),
      acceptedResourceRoles = podDef.scheduling.flatMap(_.placement).map(_.acceptedResourceRoles.toSet),
      secrets = Map.empty, // TODO: don't have this defined yet
      containers = podDef.containers,
      instances = podDef.scaling.flatMap(_.fixed).fold(1)(_.instances),
      maxInstances = podDef.scaling.flatMap(_.fixed.flatMap(_.maxInstances)),
      constraints = constraints,
      version = podDef.version.getOrElse(OffsetDateTime.now()),
      volumes = podDef.volumes,
      networks = podDef.networks
    )
  }
}