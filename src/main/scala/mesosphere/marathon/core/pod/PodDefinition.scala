package mesosphere.marathon.core.pod
// scalastyle:off
import akka.util.ByteString
import mesosphere.marathon.Protos
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.raml.{ Constraint, ConstraintOperator, EnvVar, Fixed, Label, MesosContainer, Network, PodDef, PodScalingPolicy, PodSchedulingPlacementPolicy, PodSchedulingPolicy, Volume }
import mesosphere.marathon.state.{ EnvVarSecretRef, EnvVarString, EnvVarValue, MarathonState, PathId, RunnableSpec, Secret, Timestamp }
import play.api.libs.json.{ Format, JsResult, JsValue }

import scala.collection.immutable.Seq
// scalastyle:on

/**
  * A definition for Pods.
  */
case class PodDefinition(
    id: PathId = PodDefinition.DefaultId,
    user: Option[String] = PodDefinition.DefaultUser,
    env: Map[String, EnvVarValue] = PodDefinition.DefaultEnv,
    labels: Map[String, String] = PodDefinition.DefaultLabels,
    acceptedResourceRoles: Option[Set[String]] = PodDefinition.DefaultResourceRoles,
    secrets: Map[String, Secret] = PodDefinition.DefaultSecrets,
    containers: Seq[MesosContainer] = PodDefinition.DefaultContainers,
    instances: Int = PodDefinition.DefaultInstances,
    maxInstances: Option[Int] = PodDefinition.DefaultMaxInstances,
    constraints: Set[Protos.Constraint] = PodDefinition.DefaultConstraints,
    version: Timestamp = PodDefinition.DefaultVersion,
    volumes: Seq[Volume] = PodDefinition.DefaultVolumes,
    networks: Seq[Network] = PodDefinition.DefaultNetworks
) extends RunnableSpec with MarathonState[Protos.PodDefinition, PodDefinition] {
  lazy val cpus: Double = containers.map(_.resources.cpus.toDouble).sum
  lazy val mem: Double = containers.map(_.resources.mem.toDouble).sum
  lazy val disk: Double = containers.flatMap(_.resources.disk.map(_.toDouble)).sum
  lazy val gpus: Int = containers.flatMap(_.resources.gpus).sum

  def withCanonizedIds(base: PathId = PathId.empty): PodDefinition = {
    val baseId = id.canonicalPath(base)
    copy(id = baseId)
  }

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

    val constraintDefs: Seq[Constraint] = constraints.map { c =>
      val operator = c.getOperator match {
        case Protos.Constraint.Operator.UNIQUE => ConstraintOperator.Unique
        case Protos.Constraint.Operator.CLUSTER => ConstraintOperator.Cluster
        case Protos.Constraint.Operator.GROUP_BY => ConstraintOperator.GroupBy
        case Protos.Constraint.Operator.LIKE => ConstraintOperator.Like
        case Protos.Constraint.Operator.UNLIKE => ConstraintOperator.Unlike
        case Protos.Constraint.Operator.MAX_PER => ConstraintOperator.MaxPer
      }
      Constraint(c.getField, operator, Option(c.getValue))
    }(collection.breakOut)

    // TODO: we're missing stuff here
    val schedulingPolicy = PodSchedulingPolicy(None, None,
      Some(PodSchedulingPlacementPolicy(constraintDefs, acceptedResourceRoles.fold(Seq.empty[String])(_.toVector))))

    val scalingPolicy = PodScalingPolicy(Some(Fixed(instances, maxInstances)))

    PodDef(
      id = id.safePath,
      version = Some(version.toOffsetDateTime),
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

  override def mergeFromProto(message: Protos.PodDefinition): PodDefinition = {
    PodDefinition.fromProto(message)
  }

  override def mergeFromProto(bytes: Array[Byte]): PodDefinition = {
    mergeFromProto(Protos.PodDefinition.parseFrom(bytes))
  }

  override def toProto: Protos.PodDefinition = {
    import spray.json._
    val json = asPodDef.toJson
    Protos.PodDefinition.newBuilder.setJson(json.compactPrint).build()
  }
}

object PodDefinition {
  implicit val defaultClock: Clock = Clock()

  def apply(podDef: PodDef)(implicit clock: Clock): PodDefinition = {
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
      version = podDef.version.fold(clock.now())(Timestamp(_)),
      volumes = podDef.volumes,
      networks = podDef.networks
    )
  }

  def fromProto(proto: Protos.PodDefinition): PodDefinition = {
    import spray.json._
    PodDefinition(proto.getJson.parseJson.convertTo[PodDef])
  }

  implicit val playJsonFormat: Format[PodDefinition] = new Format[PodDefinition] {
    override def reads(json: JsValue): JsResult[PodDefinition] =
      PodDef.PodDefPlayJsonFormat.reads(json).map(PodDefinition(_))

    override def writes(o: PodDefinition): JsValue = PodDef.PodDefPlayJsonFormat.writes(o.asPodDef)
  }

  def fromByteString(bs: ByteString): PodDefinition = {
    import spray.json._
    PodDefinition(bs.utf8String.parseJson.convertTo[PodDef])
  }

  def toByteString(podDef: PodDefinition): ByteString = {
    import spray.json._
    ByteString(podDef.asPodDef.toJson.compactPrint, ByteString.UTF_8)
  }

  val DefaultId = PathId.empty
  val DefaultUser = Option.empty[String]
  val DefaultEnv = Map.empty[String, EnvVarValue]
  val DefaultLabels = Map.empty[String, String]
  val DefaultResourceRoles = Option.empty[Set[String]]
  val DefaultSecrets = Map.empty[String, Secret]
  val DefaultContainers = Seq.empty[MesosContainer]
  val DefaultInstances = 1
  val DefaultMaxInstances = Option.empty[Int]
  val DefaultConstraints = Set.empty[Protos.Constraint]
  val DefaultVersion = Timestamp.now()
  val DefaultVolumes = Seq.empty[Volume]
  val DefaultNetworks = Seq.empty[Network]
}
