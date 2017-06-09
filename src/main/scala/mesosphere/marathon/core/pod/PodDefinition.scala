package mesosphere.marathon
package core.pod

// scalastyle:off
import mesosphere.marathon.api.v2.PodNormalization
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.{ Endpoint, ExecutorResources, Pod, Raml, Resources }
import mesosphere.marathon.state._
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
    constraints: Set[Protos.Constraint] = PodDefinition.DefaultConstraints,
    version: Timestamp = PodDefinition.DefaultVersion,
    podVolumes: Seq[Volume] = PodDefinition.DefaultVolumes,
    networks: Seq[Network] = PodDefinition.DefaultNetworks,
    backoffStrategy: BackoffStrategy = PodDefinition.DefaultBackoffStrategy,
    upgradeStrategy: UpgradeStrategy = PodDefinition.DefaultUpgradeStrategy,
    executorResources: Resources = PodDefinition.DefaultExecutorResources,
    override val unreachableStrategy: UnreachableStrategy = PodDefinition.DefaultUnreachableStrategy,
    override val killSelection: KillSelection = KillSelection.DefaultKillSelection
) extends RunSpec with plugin.PodSpec with MarathonState[Protos.Json, PodDefinition] {

  /**
    * As an optimization, we precompute and cache the hash of this object
    * This is done to speed up deployment plan computation.
    */
  override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)

  val endpoints: Seq[Endpoint] = containers.flatMap(_.endpoints)
  val resources = aggregateResources()

  def aggregateResources(filter: MesosContainer => Boolean = _ => true) = Resources(
    cpus = executorResources.cpus + containers.withFilter(filter).map(_.resources.cpus).sum,
    mem = executorResources.mem + containers.withFilter(filter).map(_.resources.mem).sum,
    disk = executorResources.disk + containers.withFilter(filter).map(_.resources.disk).sum,
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
    case to: PodDefinition => !isUpgrade(to) && (instances != to.instances)
    case _ => throw new IllegalStateException("Can't change pod to app")
  }

  // TODO(PODS) versionInfo
  override val versionInfo: VersionInfo = VersionInfo.OnlyVersion(version)

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
  def container(taskId: Task.Id): Option[MesosContainer] = taskId.containerName.flatMap(container(_))
  def volume(volumeName: String): Volume =
    podVolumes.find(_.name == volumeName).getOrElse(
      throw new IllegalArgumentException(s"volume named $volumeName is unknown to this pod"))
}

object PodDefinition {
  def fromProto(proto: Protos.Json): PodDefinition = {
    Raml.fromRaml(Json.parse(proto.getJson).as[Pod])
  }

  val DefaultExecutorResources: Resources = ExecutorResources().fromRaml
  val DefaultId = PathId.empty
  val DefaultUser = Option.empty[String]
  val DefaultEnv = Map.empty[String, EnvVarValue]
  val DefaultLabels = Map.empty[String, String]
  val DefaultResourceRoles = Set.empty[String]
  val DefaultSecrets = Map.empty[String, Secret]
  val DefaultContainers = Seq.empty[MesosContainer]
  val DefaultInstances = 1
  val DefaultConstraints = Set.empty[Protos.Constraint]
  val DefaultVersion = Timestamp.now()
  val DefaultVolumes = Seq.empty[Volume]
  val DefaultNetworks: Seq[Network] = PodNormalization.DefaultNetworks.map(_.fromRaml)
  val DefaultBackoffStrategy = BackoffStrategy()
  val DefaultUpgradeStrategy = AppDefinition.DefaultUpgradeStrategy
  val DefaultUnreachableStrategy = UnreachableStrategy.default(resident = false)
}
