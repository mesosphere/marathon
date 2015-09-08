package mesosphere.marathon.state

import java.lang.{ Double => JDouble, Integer => JInt }

import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ OnlyVersion, FullVersionInfo }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.apache.mesos.{ Protos => mesos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._

case class AppDefinition(

  id: PathId = AppDefinition.DefaultId,

  cmd: Option[String] = AppDefinition.DefaultCmd,

  args: Option[Seq[String]] = AppDefinition.DefaultArgs,

  user: Option[String] = AppDefinition.DefaultUser,

  env: Map[String, String] = AppDefinition.DefaultEnv,

  instances: JInt = AppDefinition.DefaultInstances,

  cpus: JDouble = AppDefinition.DefaultCpus,

  mem: JDouble = AppDefinition.DefaultMem,

  disk: JDouble = AppDefinition.DefaultDisk,

  executor: String = AppDefinition.DefaultExecutor,

  constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

  uris: Seq[String] = AppDefinition.DefaultUris,

  storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

  ports: Seq[JInt] = AppDefinition.DefaultPorts,

  requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

  backoff: FiniteDuration = AppDefinition.DefaultBackoff,

  backoffFactor: JDouble = AppDefinition.DefaultBackoffFactor,

  maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

  container: Option[Container] = AppDefinition.DefaultContainer,

  healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,

  dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

  upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

  labels: Map[String, String] = AppDefinition.DefaultLabels,

  acceptedResourceRoles: Option[Set[String]] = None,

  versionInfo: VersionInfo = VersionInfo.NoVersion)

    extends MarathonState[Protos.ServiceDefinition, AppDefinition] {

  import mesosphere.mesos.protos.Implicits._

  assert(
    portIndicesAreValid(),
    "Health check port indices must address an element of the ports array or container port mappings."
  )

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array, or if defined, the array of container
    * port mappings.
    */
  def portIndicesAreValid(): Boolean = {
    val validPortIndices = 0 until hostPorts.size
    healthChecks.forall { hc =>
      hc.protocol == Protocol.COMMAND || (validPortIndices contains hc.portIndex)
    }
  }

  //scalastyle:off method.length
  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(
      app = this,
      taskId = None,
      host = None,
      ports = Seq.empty,
      envPrefix = None
    )
    val cpusResource = ScalarResource(Resource.CPUS, cpus)
    val memResource = ScalarResource(Resource.MEM, mem)
    val diskResource = ScalarResource(Resource.DISK, disk)
    val appLabels = labels.map {
      case (key, value) =>
        mesos.Parameter.newBuilder
          .setKey(key)
          .setValue(value)
          .build
    }

    val builder = Protos.ServiceDefinition.newBuilder
      .setId(id.toString)
      .setCmd(commandInfo)
      .setInstances(instances)
      .addAllPorts(ports.asJava)
      .setRequirePorts(requirePorts)
      .setBackoff(backoff.toMillis)
      .setBackoffFactor(backoffFactor)
      .setMaxLaunchDelay(maxLaunchDelay.toMillis)
      .setExecutor(executor)
      .addAllConstraints(constraints.asJava)
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addAllHealthChecks(healthChecks.map(_.toProto).asJava)
      .setUpgradeStrategy(upgradeStrategy.toProto)
      .addAllDependencies(dependencies.map(_.toString).asJava)
      .addAllStoreUrls(storeUrls.asJava)
      .addAllLabels(appLabels.asJava)

    container.foreach { c => builder.setContainer(c.toProto()) }

    acceptedResourceRoles.foreach { acceptedResourceRoles =>
      val roles = Protos.ResourceRoles.newBuilder()
      acceptedResourceRoles.seq.foreach(roles.addRole)
      builder.setAcceptedResourceRoles(roles)
    }

    builder.setVersion(version.toString)
    versionInfo match {
      case fullInfo: FullVersionInfo =>
        builder.setLastScalingAt(fullInfo.lastScalingAt.toDateTime.getMillis)
        builder.setLastConfigChangeAt(fullInfo.lastConfigChangeAt.toDateTime.getMillis)
      case _ => // ignore
    }

    builder.build
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, String] =
      proto.getCmd.getEnvironment.getVariablesList.asScala.map {
        v => v.getName -> v.getValue
      }.toMap

    val resourcesMap: Map[String, JDouble] =
      proto.getResourcesList.asScala.map {
        r => r.getName -> (r.getScalar.getValue: JDouble)
      }.toMap

    val argsOption =
      if (proto.getCmd.getArgumentsCount > 0)
        Some(proto.getCmd.getArgumentsList.asScala.to[Seq])
      else None

    //Precondition: either args or command is defined
    val commandOption =
      if (argsOption.isEmpty && proto.getCmd.hasValue && proto.getCmd.getValue.nonEmpty)
        Some(proto.getCmd.getValue)
      else None

    val containerOption =
      if (proto.hasContainer)
        Some(Container(proto.getContainer))
      else if (proto.getCmd.hasContainer)
        Some(Container(proto.getCmd.getContainer))
      else if (proto.hasOBSOLETEContainer)
        Some(Container(proto.getOBSOLETEContainer))
      else None

    val acceptedResourceRoles: Option[Set[String]] =
      if (proto.hasAcceptedResourceRoles)
        Some(proto.getAcceptedResourceRoles.getRoleList.asScala.toSet)
      else
        None

    val versionInfoFromProto =
      if (proto.hasLastScalingAt)
        FullVersionInfo(
          version = Timestamp(proto.getVersion),
          lastScalingAt = Timestamp(proto.getLastScalingAt),
          lastConfigChangeAt = Timestamp(proto.getLastConfigChangeAt)
        )
      else
        OnlyVersion(Timestamp(proto.getVersion))

    AppDefinition(
      id = proto.getId.toPath,
      user = if (proto.getCmd.hasUser) Some(proto.getCmd.getUser) else None,
      cmd = commandOption,
      args = argsOption,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      ports = proto.getPortsList.asScala.to[Seq],
      requirePorts = proto.getRequirePorts,
      backoff = proto.getBackoff.milliseconds,
      backoffFactor = proto.getBackoffFactor,
      maxLaunchDelay = proto.getMaxLaunchDelay.milliseconds,
      constraints = proto.getConstraintsList.asScala.toSet,
      acceptedResourceRoles = acceptedResourceRoles,
      cpus = resourcesMap.getOrElse(Resource.CPUS, this.cpus),
      mem = resourcesMap.getOrElse(Resource.MEM, this.mem),
      disk = resourcesMap.getOrElse(Resource.DISK, this.disk),
      env = envMap,
      uris = proto.getCmd.getUrisList.asScala.map(_.getValue).to[Seq],
      storeUrls = proto.getStoreUrlsList.asScala.to[Seq],
      container = containerOption,
      healthChecks = proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      labels = proto.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap,
      versionInfo = versionInfoFromProto,
      upgradeStrategy =
        if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy)
        else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.asScala.map(PathId.apply).toSet
    )
  }

  def portMappings: Option[Seq[PortMapping]] =
    for {
      c <- container
      d <- c.docker
      n <- d.network if n == Network.BRIDGE
      pms <- d.portMappings
    } yield pms

  def containerHostPorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.hostPort.toInt)

  def containerServicePorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.servicePort.toInt)

  def hostPorts: Seq[Int] =
    containerHostPorts.getOrElse(ports.map(_.toInt))

  def servicePorts: Seq[Int] =
    containerServicePorts.getOrElse(ports.map(_.toInt))

  def hasDynamicPort: Boolean = servicePorts.contains(0)

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def version: Timestamp = versionInfo.version

  /**
    * Returns whether this is a scaling change only.
    */
  def isOnlyScaleChange(to: AppDefinition): Boolean =
    !isUpgrade(to) && (instances != to.instances)

  def isUpgrade(to: AppDefinition): Boolean =
    this != to.copy(instances = instances, versionInfo = versionInfo)

  /**
    * Returns the changed app definition that is marked for restarting.
    */
  def markedForRestarting: AppDefinition = copy(versionInfo = VersionInfo.NoVersion)

  /**
    * Returns true if we need to restart all tasks.
    *
    * This can either be caused by changed configuration (e.g. a new cmd, a new docker image version)
    * or by a forced restart.
    */
  def needsRestart(to: AppDefinition): Boolean =
    this.versionInfo != to.versionInfo || isUpgrade(to)

}

object AppDefinition {

  sealed trait VersionInfo {
    def version: Timestamp
    def lastConfigChangeVersion: Timestamp

    def withScaleOrRestartChange(newVersion: Timestamp): VersionInfo = {
      VersionInfo.forNewConfig(version).withScaleOrRestartChange(newVersion)
    }

    def withConfigChange(newVersion: Timestamp): VersionInfo = {
      VersionInfo.forNewConfig(newVersion)
    }
  }

  object VersionInfo {
    case object NoVersion extends VersionInfo {
      override def version: Timestamp = Timestamp(0)
      override def lastConfigChangeVersion: Timestamp = version
    }

    /**
      * Only contains a version timestamp. Will be converted to a FullVersionInfo before stored.
      */
    case class OnlyVersion(version: Timestamp) extends VersionInfo {
      override def lastConfigChangeVersion: Timestamp = version
    }

    /**
      * @param version The versioning timestamp (we are currently assuming that this is the same as lastChangeAt)
      * @param lastScalingAt The time stamp of the last change including scaling or restart changes
      * @param lastConfigChangeAt The time stamp of the last change that changed configuration
      *                               besides scaling or restarting
      */
    case class FullVersionInfo(
        version: Timestamp,
        lastScalingAt: Timestamp,
        lastConfigChangeAt: Timestamp) extends VersionInfo {

      override def lastConfigChangeVersion: Timestamp = lastConfigChangeAt

      override def withScaleOrRestartChange(newVersion: Timestamp): VersionInfo = {
        copy(version = newVersion, lastScalingAt = newVersion)
      }
    }

    def forNewConfig(newVersion: Timestamp): FullVersionInfo = FullVersionInfo(
      version = newVersion,
      lastScalingAt = newVersion,
      lastConfigChangeAt = newVersion
    )
  }

  val RandomPortValue: Int = 0

  // App defaults
  val DefaultId: PathId = PathId.empty

  val DefaultCmd: Option[String] = None

  val DefaultArgs: Option[Seq[String]] = None

  val DefaultUser: Option[String] = None

  val DefaultEnv: Map[String, String] = Map.empty

  val DefaultInstances: Int = 1

  val DefaultCpus: Double = 1.0

  val DefaultMem: Double = 128.0

  val DefaultDisk: Double = 0.0

  val DefaultExecutor: String = ""

  val DefaultConstraints: Set[Constraint] = Set.empty

  val DefaultUris: Seq[String] = Seq.empty

  val DefaultStoreUrls: Seq[String] = Seq.empty

  val DefaultPorts: Seq[JInt] = Seq(RandomPortValue)

  val DefaultRequirePorts: Boolean = false

  val DefaultBackoff: FiniteDuration = 1.second

  val DefaultBackoffFactor = 1.15

  val DefaultMaxLaunchDelay: FiniteDuration = 1.hour

  val DefaultContainer: Option[Container] = None

  val DefaultHealthChecks: Set[HealthCheck] = Set.empty

  val DefaultDependencies: Set[PathId] = Set.empty

  val DefaultUpgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty

  val DefaultLabels: Map[String, String] = Map.empty

  /**
    * This default is only used in tests
    */
  val DefaultAcceptedResourceRoles: Set[String] = Set.empty

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)
}
