package mesosphere.marathon.state

import com.wix.accord._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.serialization.{ ContainerSerializer, PortDefinitionSerializer, ResidencySerializer }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.plugin
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ FullVersionInfo, OnlyVersion }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => mesos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._

import com.wix.accord.dsl._

case class AppDefinition(

  id: PathId = AppDefinition.DefaultId,

  cmd: Option[String] = AppDefinition.DefaultCmd,

  args: Option[Seq[String]] = AppDefinition.DefaultArgs,

  user: Option[String] = AppDefinition.DefaultUser,

  env: Map[String, String] = AppDefinition.DefaultEnv,

  instances: Int = AppDefinition.DefaultInstances,

  cpus: Double = AppDefinition.DefaultCpus,

  mem: Double = AppDefinition.DefaultMem,

  disk: Double = AppDefinition.DefaultDisk,

  executor: String = AppDefinition.DefaultExecutor,

  constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

  fetch: Seq[FetchUri] = AppDefinition.DefaultFetch,

  storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

  portDefinitions: Seq[PortDefinition] = AppDefinition.DefaultPortDefinitions,

  requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

  backoff: FiniteDuration = AppDefinition.DefaultBackoff,

  backoffFactor: Double = AppDefinition.DefaultBackoffFactor,

  maxLaunchDelay: FiniteDuration = AppDefinition.DefaultMaxLaunchDelay,

  container: Option[Container] = AppDefinition.DefaultContainer,

  healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,

  dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

  upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

  labels: Map[String, String] = AppDefinition.DefaultLabels,

  acceptedResourceRoles: Option[Set[String]] = None,

  ipAddress: Option[IpAddress] = None,

  versionInfo: VersionInfo = VersionInfo.NoVersion,

  residency: Option[Residency] = AppDefinition.DefaultResidency)

    extends plugin.AppDefinition with MarathonState[Protos.ServiceDefinition, AppDefinition] {

  import mesosphere.mesos.protos.Implicits._

  require(ipAddress.isEmpty || portDefinitions.isEmpty, "IP address and ports are not allowed at the same time")

  lazy val portNumbers: Seq[Int] = portDefinitions.map(_.port)

  /**
    * Returns true if all health check port index values are in the range
    * of ths app's ports array, or if defined, the array of container
    * port mappings.
    */
  def portIndicesAreValid(): Boolean = {
    val validPortIndices = hostPorts.indices
    healthChecks.forall { hc =>
      hc.protocol == Protocol.COMMAND || hc.portIndex.forall(validPortIndices.contains(_))
    }
  }

  def isResident: Boolean = residency.isDefined

  def persistentVolumes: Iterable[PersistentVolume] = {
    container.fold(Seq.empty[Volume])(_.volumes).collect{ case vol: PersistentVolume => vol }
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
      .addAllPortDefinitions(portDefinitions.map(PortDefinitionSerializer.toProto).asJava)
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

    ipAddress.foreach { ip => builder.setIpAddress(ip.toProto) }

    container.foreach { c => builder.setContainer(ContainerSerializer.toProto(c)) }

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

    residency.foreach { r => builder.setResidency(ResidencySerializer.toProto(r)) }

    builder.build
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, String] =
      proto.getCmd.getEnvironment.getVariablesList.asScala.map {
        v => v.getName -> v.getValue
      }.toMap

    val resourcesMap: Map[String, Double] =
      proto.getResourcesList.asScala.map {
        r => r.getName -> (r.getScalar.getValue: Double)
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

    val containerOption = if (proto.hasContainer) Some(ContainerSerializer.fromProto(proto.getContainer)) else None

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

    val ipAddressOption = if (proto.hasIpAddress) Some(IpAddress.fromProto(proto.getIpAddress)) else None

    val residencyOption = if (proto.hasResidency) Some(ResidencySerializer.fromProto(proto.getResidency)) else None

    // TODO (gkleiman): we have to be able to read the ports from the deprecated field in order to perform migrations
    // until the deprecation cycle is complete.
    val portDefinitions =
      if (proto.getPortsCount > 0) PortDefinitions(proto.getPortsList.asScala.map(_.intValue): _*)
      else proto.getPortDefinitionsList.asScala.map(PortDefinitionSerializer.fromProto).to[Seq]

    AppDefinition(
      id = PathId(proto.getId),
      user = if (proto.getCmd.hasUser) Some(proto.getCmd.getUser) else None,
      cmd = commandOption,
      args = argsOption,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      portDefinitions = portDefinitions,
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
      fetch = proto.getCmd.getUrisList.asScala.map(FetchUri.fromProto).to[Seq],
      storeUrls = proto.getStoreUrlsList.asScala.to[Seq],
      container = containerOption,
      healthChecks = proto.getHealthChecksList.asScala.map(new HealthCheck().mergeFromProto).toSet,
      labels = proto.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap,
      versionInfo = versionInfoFromProto,
      upgradeStrategy =
        if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy)
        else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.asScala.map(PathId.apply).toSet,
      ipAddress = ipAddressOption,
      residency = residencyOption
    )
  }

  def portMappings: Option[Seq[PortMapping]] =
    for {
      c <- container
      d <- c.docker
      n <- d.network if n == DockerInfo.Network.BRIDGE
      pms <- d.portMappings
    } yield pms

  def containerHostPorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.hostPort)

  def containerServicePorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.servicePort)

  def hostPorts: Seq[Int] = containerHostPorts.getOrElse(portNumbers)

  def servicePorts: Seq[Int] = containerServicePorts.getOrElse(portNumbers)

  def hasDynamicPort: Boolean = servicePorts.contains(AppDefinition.RandomPortValue)

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  def version: Timestamp = versionInfo.version

  /**
    * Returns whether this is a scaling change only.
    */
  def isOnlyScaleChange(to: AppDefinition): Boolean = !isUpgrade(to) && (instances != to.instances)

  /**
    * True if the given app definition is a change to the current one in terms of runtime characteristics
    * of all deployed tasks of the current app, otherwise false.
    */
  def isUpgrade(to: AppDefinition): Boolean = {
    id == to.id && {
      cmd != to.cmd ||
        args != to.args ||
        user != to.user ||
        env != to.env ||
        cpus != to.cpus ||
        mem != to.mem ||
        disk != to.disk ||
        executor != to.executor ||
        constraints != to.constraints ||
        fetch != to.fetch ||
        storeUrls != to.storeUrls ||
        portDefinitions != to.portDefinitions ||
        requirePorts != to.requirePorts ||
        backoff != to.backoff ||
        backoffFactor != to.backoffFactor ||
        maxLaunchDelay != to.maxLaunchDelay ||
        container != to.container ||
        healthChecks != to.healthChecks ||
        dependencies != to.dependencies ||
        upgradeStrategy != to.upgradeStrategy ||
        labels != to.labels ||
        acceptedResourceRoles != to.acceptedResourceRoles ||
        ipAddress != to.ipAddress
    }
  }

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
  def needsRestart(to: AppDefinition): Boolean = this.versionInfo != to.versionInfo || isUpgrade(to)

  /**
    * Identify other app definitions as the same, if id and version is the same.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def equals(obj: Any): Boolean = {
    obj match {
      case that: AppDefinition => (that eq this) || (that.id == id && that.version == version)
      case _                   => false
    }
  }

  /**
    * Compute the hashCode of an app only by id.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def hashCode(): Int = id.hashCode()

  def withCanonizedIds(base: PathId = PathId.empty): AppDefinition = {
    val baseId = id.canonicalPath(base)
    copy(id = baseId, dependencies = dependencies.map(_.canonicalPath(baseId)))
  }
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

    /**
      * This should only be used for new AppDefinitions.
      *
      * If you set the versionInfo of existing AppDefinitions to `NoVersion`,
      * it will result in a restart when this AppDefinition is passed to the GroupManager update method.
      */
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
  val RandomPortDefinition: PortDefinition = PortDefinition(RandomPortValue, "tcp", None, Map.empty[String, String])

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

  val DefaultFetch: Seq[FetchUri] = FetchUri.empty

  val DefaultStoreUrls: Seq[String] = Seq.empty

  val DefaultPortDefinitions: Seq[PortDefinition] = Seq(RandomPortDefinition)

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

  val DefaultResidency: Option[Residency] = None

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)

  /**
    * We cannot validate HealthChecks here, because it would break backwards compatibility in weird ways.
    * If users had already one invalid app definition, each deployment would cause a complete revalidation of
    * the root group including the invalid one.
    * Until the user changed all invalid apps, the user would get weird validation
    * errors for every deployment potentially unrelated to the deployed apps.
    */
  implicit val appDefinitionValidator = validator[AppDefinition] { appDef =>
    appDef.id is valid
    appDef.dependencies is valid
    appDef.upgradeStrategy is valid
    appDef.container.each is valid
    appDef.storeUrls is every(urlCanBeResolvedValidator)
    appDef.portDefinitions is PortDefinitions.portDefinitionsValidator
    appDef.executor should matchRegexFully("^(//cmd)|(/?[^/]+(/[^/]+)*)|$")
    appDef is containsCmdArgsContainerValidator
    appDef.healthChecks is every(portIndixIsValid(appDef.hostPorts.indices))
    appDef.instances should be >= 0
    appDef.fetch is every(fetchUriIsValid)
    (appDef.persistentVolumes is empty) or (appDef.residency is notEmpty)
  }

  def filterOutRandomPorts(ports: scala.Seq[Int]): scala.Seq[Int] = {
    ports.filterNot(_ == AppDefinition.RandomPortValue)
  }

  private def containsCmdArgsContainerValidator: Validator[AppDefinition] = {
    new Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        val cmd = app.cmd.nonEmpty
        val args = app.args.nonEmpty
        val container = app.container.exists(_ != Container.Empty)
        if ((cmd ^ args) || (!(cmd || args) && container)) Success
        else Failure(Set(RuleViolation(app,
          "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.", None)))
      }
    }
  }

  private def portIndixIsValid(hostPortsIndices: Range) = new Validator[HealthCheck] {
    override def apply(healthCheck: HealthCheck): Result = {
      if (healthCheck.protocol == Protocol.COMMAND || (healthCheck.portIndex match {
        case Some(idx) => hostPortsIndices contains idx
        case None      => hostPortsIndices.length == 1 && hostPortsIndices.head == 0
      })) Success
      else Failure(Set(RuleViolation(healthCheck,
        "Health check port indices must address an element of the ports array or container port mappings.", None)))
    }
  }

  def residentUpdateIsValid(from: AppDefinition): Validator[AppDefinition] = {
    def changeNoVolumes: Validator[AppDefinition] = new Validator[AppDefinition] {
      override def apply(to: AppDefinition): Result = {
        val fromVolumes = from.persistentVolumes
        val toVolumes = to.persistentVolumes
        def sameSize = fromVolumes.size == toVolumes.size
        def noChange = from.persistentVolumes.forall { fromVolume =>
          toVolumes.find(_.containerPath == fromVolume.containerPath).contains(fromVolume)
        }
        if (sameSize && noChange) Success
        else Failure(Set(RuleViolation(to, "Persistent volumes can not be changed!", None)))
      }
    }
    def changeNoResources: Validator[AppDefinition] = new Validator[AppDefinition] {
      override def apply(to: AppDefinition): Result = {
        if (from.cpus != to.cpus ||
          from.mem != to.mem ||
          from.disk != to.disk ||
          from.portDefinitions != to.portDefinitions)
          Failure(Set(RuleViolation(to, "Resident Tasks may not change resource requirements!", None)))
        else
          Success
      }
    }
    validator[AppDefinition] { app =>
      app should changeNoVolumes
      app should changeNoResources
      app.upgradeStrategy is UpgradeStrategy.validForResidentTasks
    }
  }

  def updateIsValid(from: Group): Validator[AppDefinition] = {
    new Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        from.transitiveApps.find(_.id == app.id) match {
          case (Some(last)) if last.isResident || app.isResident => residentUpdateIsValid(last)(app)
          case _ => Success
        }
      }
    }
  }
}
