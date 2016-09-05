package mesosphere.marathon.core.pod
// scalastyle:off
import mesosphere.marathon.Protos
import mesosphere.marathon.raml.{ Volume, Constraint => RamlConstraint, EnvVarSecretRef => RamlEnvVarSecretRef, EnvVarValue => RamlEnvVarValue, _ }
import mesosphere.marathon.state._
import play.api.libs.json.{ Format, JsResult, JsValue, Json }

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
    networks: Seq[Network] = PodDefinition.DefaultNetworks,
    backoffStrategy: Option[PodSchedulingBackoffStrategy] = None,
    upgradeStrategy: Option[PodUpgradeStrategy] = None
) extends RunnableSpec with MarathonState[Protos.PodDefinition, PodDefinition] {
  lazy val cpus: Double = containers.map(_.resources.cpus.toDouble).sum
  lazy val mem: Double = containers.map(_.resources.mem.toDouble).sum
  lazy val disk: Double = containers.flatMap(_.resources.disk.map(_.toDouble)).sum
  lazy val gpus: Int = containers.flatMap(_.resources.gpus).sum

  lazy val asPodDef: Pod = {
    val envVars: EnvVars = EnvVars(env.mapValues {
      case EnvVarSecretRef(secret) =>
        RamlEnvVarSecretRef(secret)
      case EnvVarString(value) =>
        RamlEnvVarValue(value)
    }
    )

    val constraintDefs: Seq[RamlConstraint] = constraints.map { c =>
      val operator = c.getOperator match {
        case Protos.Constraint.Operator.UNIQUE => ConstraintOperator.Unique
        case Protos.Constraint.Operator.CLUSTER => ConstraintOperator.Cluster
        case Protos.Constraint.Operator.GROUP_BY => ConstraintOperator.GroupBy
        case Protos.Constraint.Operator.LIKE => ConstraintOperator.Like
        case Protos.Constraint.Operator.UNLIKE => ConstraintOperator.Unlike
        case Protos.Constraint.Operator.MAX_PER => ConstraintOperator.MaxPer
      }
      RamlConstraint(c.getField, operator, Option(c.getValue))
    }(collection.breakOut)

    // TODO: we're missing stuff here
    val schedulingPolicy = PodSchedulingPolicy(backoffStrategy, upgradeStrategy,
      Some(PodPlacementPolicy(constraintDefs, acceptedResourceRoles.fold(Seq.empty[String])(_.toVector))))

    val scalingPolicy = FixedPodScalingPolicy(instances, maxInstances)

    Pod(
      id = id.safePath,
      version = Some(version.toOffsetDateTime),
      user = user,
      containers = containers,
      environment = Some(envVars),
      labels = Some(KVLabels(labels)),
      scaling = Some(scalingPolicy),
      scheduling = Some(schedulingPolicy),
      volumes = volumes,
      networks = networks
    )
  }

  override def mergeFromProto(message: Protos.PodDefinition): PodDefinition = {
    PodDefinition(Json.parse(message.getJson).as[Pod])
  }

  override def mergeFromProto(bytes: Array[Byte]): PodDefinition = {
    mergeFromProto(Protos.PodDefinition.parseFrom(bytes))
  }

  override def toProto: Protos.PodDefinition = {
    val json = Json.toJson(asPodDef)
    Protos.PodDefinition.newBuilder.setJson(Json.stringify(json)).build()
  }

  // TODO (pods): how would or should we correctly implement this?
  override lazy val lastConfigChangeVersion: Timestamp = Timestamp(version.toDateTime)
}

object PodDefinition {
  def apply(podDef: Pod): PodDefinition = {
    val env: Map[String, EnvVarValue] =
      podDef.environment.fold(Map.empty[String, EnvVarValue]) { envvars =>
        envvars.values.map { e =>
          e._1 -> (e._2 match {
            case RamlEnvVarSecretRef(secretRef) =>
              EnvVarSecretRef(secretRef)
            case RamlEnvVarValue(literalValue) =>
              EnvVarString(literalValue)
          })
        }
      }

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

    new PodDefinition(
      id = PathId(podDef.id),
      user = podDef.user,
      env = env,
      labels = podDef.labels.fold(Map.empty[String, String])(_.values),
      acceptedResourceRoles = podDef.scheduling.flatMap(_.placement).map(_.acceptedResourceRoles.toSet),
      secrets = podDef.secrets.fold(Map.empty[String, Secret])(_.values.mapValues(s => Secret(s.source))),
      containers = podDef.containers,
      instances = instances,
      maxInstances = maxInstances,
      constraints = constraints,
      version = podDef.version.fold(Timestamp.now())(Timestamp(_)),
      volumes = podDef.volumes,
      networks = podDef.networks,
      backoffStrategy = podDef.scheduling.flatMap(_.backoff),
      upgradeStrategy = podDef.scheduling.flatMap(_.upgrade)
    )
  }

  def fromProto(proto: Protos.PodDefinition): PodDefinition = {
    PodDefinition(Json.parse(proto.getJson).as[Pod])
  }

  implicit val playJsonFormat: Format[PodDefinition] = new Format[PodDefinition] {
    override def reads(json: JsValue): JsResult[PodDefinition] =
      Pod.PlayJsonFormat.reads(json).map(PodDefinition(_))

    override def writes(o: PodDefinition): JsValue = Pod.PlayJsonFormat.writes(o.asPodDef)
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
