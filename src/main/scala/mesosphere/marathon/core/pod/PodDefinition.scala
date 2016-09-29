package mesosphere.marathon.core.pod
// scalastyle:off
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.{ Pod, Raml, Resources }
import mesosphere.marathon.state.{ AppDefinition, BackoffStrategy, EnvVarValue, IpAddress, MarathonState, PathId, PortAssignment, Residency, RunSpec, Secret, Timestamp, UpgradeStrategy, VersionInfo }
import mesosphere.marathon.{ Protos, plugin }
import play.api.libs.json.Json

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
) extends RunSpec with plugin.PodSpec with MarathonState[Protos.Json, PodDefinition] {

  val resources = aggregateResources()

  def aggregateResources(filter: MesosContainer => Boolean = _ => true) = Resources(
    cpus = PodDefinition.DefaultExecutorResources.cpus + containers.withFilter(filter).map(_.resources.cpus).sum,
    mem = PodDefinition.DefaultExecutorResources.mem + containers.withFilter(filter).map(_.resources.mem).sum,
    disk = PodDefinition.DefaultExecutorResources.disk + containers.withFilter(filter).map(_.resources.disk).sum,
    gpus = containers.withFilter(filter).map(_.resources.gpus).sum
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
          networks != to.networks ||
          backoffStrategy != to.backoffStrategy ||
          upgradeStrategy != to.upgradeStrategy
      }
    case _ =>
      // A validation rule will ensure, this can not happen
      throw new IllegalStateException("Can't change pod to app")
  }
  // scalastyle:on

  override def needsRestart(to: RunSpec): Boolean = this.version != to.version || isUpgrade(to)

  override def isOnlyScaleChange(to: RunSpec): Boolean = to match {
    case to: PodDefinition => !isUpgrade(to) && (instances != to.instances || maxInstances != to.maxInstances)
    case _ => throw new IllegalStateException("Can't change pod to app")
  }

  // TODO(PODS) versionInfo
  override val versionInfo: VersionInfo = VersionInfo.OnlyVersion(version)

  override val residency = Option.empty[Residency]
  override val healthChecks = Set.empty[HealthCheck]
  override val readinessChecks = Seq.empty[ReadinessCheck]

  // TODO(PODS) PortAssignments
  override def portAssignments(task: Task): Seq[PortAssignment] = Seq.empty[PortAssignment]

  // TODO(PODS) ipaddress? is this even supported?
  override val ipAddress = Option.empty[IpAddress]

  override def mergeFromProto(message: Protos.Json): PodDefinition = {
    Raml.fromRaml(Json.parse(message.getJson).as[Pod])
  }

  override def mergeFromProto(bytes: Array[Byte]): PodDefinition = {
    mergeFromProto(Protos.Json.parseFrom(bytes))
  }

  override def toProto: Protos.Json = {
    val json = Json.toJson(Raml.toRaml(this))
    Protos.Json.newBuilder.setJson(Json.stringify(json)).build()
  }

  def container(name: String): Option[MesosContainer] = containers.find(_.name == name)
  def container(taskId: Task.Id): Option[MesosContainer] = taskId.containerName.flatMap(container)
  def volume(volumeName: String): Volume =
    podVolumes.find(_.name == volumeName).getOrElse(
      throw new IllegalArgumentException(s"volume named ${volumeName} is unknown to this pod"))
}

object PodDefinition {
  def fromProto(proto: Protos.Json): PodDefinition = {
    Raml.fromRaml(Json.parse(proto.getJson).as[Pod])
  }

  val DefaultExecutorResources = Resources(cpus = 0.1, mem = 32.0, disk = 10.0, gpus = 0)
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
