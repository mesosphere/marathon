package mesosphere.marathon.core.pod
// scalastyle:off
import mesosphere.marathon.Protos
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.{ ConstraintOperator, EnvVars, FixedPodScalingPolicy, KVLabels, MesosContainer, Network, Pod, PodPlacementPolicy, PodSchedulingBackoffStrategy, PodSchedulingPolicy, PodUpgradeStrategy, Resources, Volume, Constraint => RamlConstraint, EnvVarSecretRef => RamlEnvVarSecretRef, EnvVarValue => RamlEnvVarValue }
import mesosphere.marathon.state.{ AppDefinition, BackoffStrategy, EnvVarSecretRef, EnvVarString, EnvVarValue, IpAddress, MarathonState, PathId, PortAssignment, Residency, RunSpec, Secret, Timestamp, UpgradeStrategy, VersionInfo }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._
// scalastyle:on

/**
  * A definition for Pods.
  */
case class PodDefinition(
    id: PathId = PodDefinition.DefaultId,
    user: Option[String] = PodDefinition.DefaultUser,
    env: Map[String, EnvVarValue] = PodDefinition.DefaultEnv,
    labels: Map[String, String] = PodDefinition.DefaultLabels,
    acceptedResourceRoles: Set[String] = PodDefinition.DefaultResourceRoles,
    secrets: Map[String, Secret] = PodDefinition.DefaultSecrets,
    containers: Seq[MesosContainer] = PodDefinition.DefaultContainers,
    instances: Int = PodDefinition.DefaultInstances,
    maxInstances: Option[Int] = PodDefinition.DefaultMaxInstances,
    constraints: Set[Protos.Constraint] = PodDefinition.DefaultConstraints,
    version: Timestamp = PodDefinition.DefaultVersion,
    podVolumes: Seq[Volume] = PodDefinition.DefaultVolumes,
    networks: Seq[Network] = PodDefinition.DefaultNetworks,
    backoffStrategy: BackoffStrategy = PodDefinition.DefaultBackoffStrategy,
    upgradeStrategy: UpgradeStrategy = PodDefinition.DefaultUpgradeStrategy
) extends RunSpec with MarathonState[Protos.PodDefinition, PodDefinition] {

  val resources = Resources(
    cpus = PodDefinition.DefaultExecutorCpus + containers.map(_.resources.cpus).sum,
    mem = PodDefinition.DefaultExecutorMem + containers.map(_.resources.mem).sum,
    disk = containers.map(_.resources.disk).sum,
    gpus = containers.map(_.resources.gpus).sum
  )

  override def withInstances(instances: Int): RunSpec = copy(instances = instances)

  // scalastyle:off cyclomatic.complexity
  override def isUpgrade(to: RunSpec): Boolean = to match {
    case to: PodDefinition =>
      id == to.id && {
        user != to.user ||
          env != to.env ||
          labels != to.labels ||
          acceptedResourceRoles != to.acceptedResourceRoles ||
          secrets != to.secrets ||
          containers != to.containers ||
          constraints != to.constraints ||
          podVolumes != to.podVolumes ||
          networks != to.networks
        // TODO(PODS): upgrade and backoffStrategy?
      }
    case _ =>
      // TODO(PODS) can this even be reached at all?
      throw new IllegalStateException("Can't change pod to app")
  }
  // scalastyle:on

  // TODO(PODS) needsRestart for pod - is this right?
  override def needsRestart(to: RunSpec): Boolean = this.version != to.version || isUpgrade(to)

  override def isOnlyScaleChange(to: RunSpec): Boolean = to match {
    case to: PodDefinition =>
      !isUpgrade(to) && (instances != to.instances || maxInstances != to.maxInstances)
    case _ =>
      // TODO(PODS) can this even be reached at all?
      throw new IllegalStateException("Can't change pod to app")
  }

  // TODO(PODS) versionInfo
  override val versionInfo: VersionInfo = VersionInfo.OnlyVersion(version)

  override val residency = Option.empty[Residency]
  // TODO(PODS) healthChecks
  override val healthChecks = Set.empty[HealthCheck]

  override val readinessChecks = Seq.empty[ReadinessCheck]
  // TODO(PODS) PortAssignments
  override def portAssignments(task: Task): Seq[PortAssignment] = Seq.empty[PortAssignment]

  // TODO(PODS) ipaddress? is this even supported?
  override val ipAddress = Option.empty[IpAddress]
  lazy val asPodDef: Pod = {
    val envVars: EnvVars = EnvVars(env.mapValues {
      case EnvVarSecretRef(secret) =>
        RamlEnvVarSecretRef(secret)
      case EnvVarString(value) =>
        RamlEnvVarValue(value)
    })

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

    val ramlUpgradeStrategy = PodUpgradeStrategy(
      upgradeStrategy.minimumHealthCapacity,
      upgradeStrategy.maximumOverCapacity)

    val ramlBackoffStrategy = PodSchedulingBackoffStrategy(
      backoff = backoffStrategy.backoff.toMillis.toDouble / 1000.0,
      maxLaunchDelay = backoffStrategy.maxLaunchDelay.toMillis.toDouble / 1000.0,
      backoffFactor = backoffStrategy.factor)
    val schedulingPolicy = PodSchedulingPolicy(Some(ramlBackoffStrategy), Some(ramlUpgradeStrategy),
      Some(PodPlacementPolicy(constraintDefs, acceptedResourceRoles.toVector)))

    val scalingPolicy = FixedPodScalingPolicy(instances, maxInstances)

    Pod(
      id = id.toString,
      version = Some(version.toOffsetDateTime),
      user = user,
      containers = containers,
      environment = Some(envVars),
      labels = Some(KVLabels(labels)),
      scaling = Some(scalingPolicy),
      scheduling = Some(schedulingPolicy),
      volumes = podVolumes,
      networks = networks
    )
  }

  override def mergeFromProto(message: Protos.PodDefinition): PodDefinition = {
    PodDefinition(Json.parse(message.getJson).as[Pod], None)
  }

  override def mergeFromProto(bytes: Array[Byte]): PodDefinition = {
    mergeFromProto(Protos.PodDefinition.parseFrom(bytes))
  }

  override def toProto: Protos.PodDefinition = {
    val json = Json.toJson(asPodDef)
    Protos.PodDefinition.newBuilder.setJson(Json.stringify(json)).build()
  }
}

object PodDefinition {

  //scalastyle:off
  def apply(podDef: Pod, defaultNetworkName: Option[String]): PodDefinition = {
    val env: Map[String, EnvVarValue] =
      podDef.environment.fold(Map.empty[String, EnvVarValue]) {
        _.values.mapValues {
          case RamlEnvVarSecretRef(secretRef) =>
            EnvVarSecretRef(secretRef)
          case RamlEnvVarValue(literalValue) =>
            EnvVarString(literalValue)
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

    val networks = podDef.networks.map { network =>
      if (network.name.isEmpty) {
        network.copy(name = defaultNetworkName)
      } else {
        network
      }
    }

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
      env = env,
      labels = podDef.labels.fold(Map.empty[String, String])(_.values),
      acceptedResourceRoles = resourceRoles,
      secrets = podDef.secrets.fold(Map.empty[String, Secret])(_.values.mapValues(s => Secret(s.source))),
      containers = podDef.containers,
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

  def fromProto(proto: Protos.PodDefinition): PodDefinition = {
    PodDefinition(Json.parse(proto.getJson).as[Pod], None)
  }

  val DefaultExecutorCpus: Double = 0.1
  val DefaultExecutorMem: Double = 32.0
  val DefaultId = PathId.empty
  val DefaultUser = Option.empty[String]
  val DefaultEnv = Map.empty[String, EnvVarValue]
  val DefaultLabels = Map.empty[String, String]
  val DefaultResourceRoles = Set.empty[String]
  val DefaultSecrets = Map.empty[String, Secret]
  val DefaultContainers = Seq.empty[MesosContainer]
  val DefaultInstances = 1
  val DefaultMaxInstances = Option.empty[Int]
  val DefaultConstraints = Set.empty[Protos.Constraint]
  val DefaultVersion = Timestamp.now()
  val DefaultVolumes = Seq.empty[Volume]
  val DefaultNetworks = Seq.empty[Network]
  val DefaultBackoffStrategy = BackoffStrategy()
  val DefaultUpgradeStrategy = AppDefinition.DefaultUpgradeStrategy
}
