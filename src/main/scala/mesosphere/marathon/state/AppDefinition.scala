package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.serialization.{ ContainerSerializer, PortDefinitionSerializer, ResidencySerializer }
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ FullVersionInfo, OnlyVersion }
import mesosphere.marathon.state.AppDefinition.{ Labels, VersionInfo }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.{ Protos, plugin }
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => mesos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._

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

  readinessChecks: Seq[ReadinessCheck] = AppDefinition.DefaultReadinessChecks,

  dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

  upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

  labels: Map[String, String] = Labels.Default,

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
    val validPortIndices = portIndices
    healthChecks.forall { hc =>
      hc.protocol == Protocol.COMMAND || hc.portIndex.forall(validPortIndices.contains(_))
    }
  }

  def isResident: Boolean = residency.isDefined

  def isSingleInstance: Boolean = labels.get(Labels.SingleInstanceApp).contains("true")
  def volumes: Iterable[Volume] = container.fold(Seq.empty[Volume])(_.volumes)
  def persistentVolumes: Iterable[PersistentVolume] = volumes.collect { case vol: PersistentVolume => vol }
  def externalVolumes: Iterable[ExternalVolume] = volumes.collect { case vol: ExternalVolume => vol }

  def diskForPersistentVolumes: Double = persistentVolumes.map(_.persistent.size).sum.toDouble

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
    readinessChecks.foreach { r => builder.addReadinessCheckDefinition(ReadinessCheckSerializer.toProto(r)) }

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
      healthChecks = proto.getHealthChecksList.iterator().asScala.map(new HealthCheck().mergeFromProto).toSet,
      readinessChecks =
        proto.getReadinessCheckDefinitionList.iterator().asScala.map(ReadinessCheckSerializer.fromProto).to[Seq],
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

  def portIndices: Range = containerHostPorts.getOrElse(portNumbers).indices

  def servicePorts: Seq[Int] = containerServicePorts.getOrElse(portNumbers)

  def hasDynamicPort: Boolean = servicePorts.contains(AppDefinition.RandomPortValue)

  def bridgeMode: Boolean =
    container.flatMap(_.docker.map(_.network == Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE))).getOrElse(false)

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
        ipAddress != to.ipAddress ||
        readinessChecks != to.readinessChecks ||
        residency != to.residency
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

  def portAssignments(task: Task): Option[Seq[PortAssignment]] = {
    def fromIpAddress: Option[Seq[PortAssignment]] = ipAddress.flatMap {
      case IpAddress(_, _, DiscoveryInfo(appPorts)) =>
        for {
          launched <- task.launched
          effectiveIpAddress <- task.effectiveIpAddress(this)
        } yield appPorts.zip(launched.hostPorts).zipWithIndex.map {
          case ((appPort, hostPort), portIndex) =>
            PortAssignment(Some(appPort.name), portIndex, effectiveIpAddress, hostPort)
        }.toList
    }

    def fromPortMappings: Option[Seq[PortAssignment]] =
      for {
        pms <- portMappings
        launched <- task.launched
      } yield pms.zip(launched.hostPorts).zipWithIndex.map {
        case ((portMapping, hostPort), portIndex) =>
          PortAssignment(portMapping.name, portIndex, task.agentInfo.host, hostPort)
      }.toList

    def fromPortDefinitions: Option[Seq[PortAssignment]] = task.launched.map { launched =>
      portDefinitions.zip(launched.hostPorts).zipWithIndex.map {
        case ((portDefinition, hostPort), portIndex) =>
          PortAssignment(portDefinition.name, portIndex, task.agentInfo.host, hostPort)
      }
    }

    if (ipAddress.isDefined) fromIpAddress
    else if (bridgeMode) fromPortMappings
    else fromPortDefinitions
  }

  def portNames: Seq[String] = {
    def fromIpAddress = ipAddress.map(_.discoveryInfo.ports.map(_.name).toList).getOrElse(Seq.empty)
    def fromPortMappings = portMappings.getOrElse(Seq.empty).flatMap(_.name)
    def fromPortDefinitions = portDefinitions.flatMap(_.name)

    if (ipAddress.isDefined) fromIpAddress
    else if (bridgeMode) fromPortMappings
    else fromPortDefinitions
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
      *                           besides scaling or restarting
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

  val DefaultReadinessChecks: Seq[ReadinessCheck] = Seq.empty

  val DefaultDependencies: Set[PathId] = Set.empty

  val DefaultUpgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty

  object Labels {
    val Default: Map[String, String] = Map.empty

    val DcosMigrationApiPath = "DCOS_MIGRATION_API_PATH"
    val DcosMigrationApiVersion = "DCOS_MIGRATION_API_VERSION"
    val DcosPackageFrameworkName = "DCOS_PACKAGE_FRAMEWORK_NAME"
    val SingleInstanceApp = "MARATHON_SINGLE_INSTANCE_APP"
  }

  /**
    * This default is only used in tests
    */
  val DefaultAcceptedResourceRoles: Set[String] = Set.empty

  val DefaultResidency: Option[Residency] = None

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition().mergeFromProto(proto)

  private val validBasicAppDefinition = validator[AppDefinition] { appDef =>
    appDef.upgradeStrategy is valid
    appDef.container.each is valid
    appDef.storeUrls is every(urlCanBeResolvedValidator)
    appDef.portDefinitions is PortDefinitions.portDefinitionsValidator
    appDef.executor should matchRegexFully("^(//cmd)|(/?[^/]+(/[^/]+)*)|$")
    appDef is containsCmdArgsOrContainer
    appDef.healthChecks is every(portIndexIsValid(appDef.portIndices))
    appDef.instances should be >= 0
    appDef.fetch is every(fetchUriIsValid)
    appDef.mem should be >= 0.0
    appDef.cpus should be >= 0.0
    appDef.instances should be >= 0
    appDef.disk should be >= 0.0
    appDef must complyWithResourceRoleRules
    appDef must complyWithResidencyRules
    appDef must complyWithMigrationAPI
    appDef must complyWithSingleInstanceLabelRules
    appDef must complyWithReadinessCheckRules
    appDef must complyWithUpgradeStrategyRules
  } and ExternalVolumes.validApp

  /**
    * We cannot validate HealthChecks here, because it would break backwards compatibility in weird ways.
    * If users had already one invalid app definition, each deployment would cause a complete revalidation of
    * the root group including the invalid one.
    * Until the user changed all invalid apps, the user would get weird validation
    * errors for every deployment potentially unrelated to the deployed apps.
    */
  implicit val validAppDefinition: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app.id is valid
    app.id is PathId.absolutePathValidator
    app.dependencies is every(PathId.validPathWithBase(app.id.parent))
  } and validBasicAppDefinition

  /**
    * Validator for apps, which are being part of a group.
    *
    * @param base Path of the parent group.
    * @return
    */
  def validNestedAppDefinition(base: PathId): Validator[AppDefinition] = validator[AppDefinition] { app =>
    app.id is PathId.validPathWithBase(base)
  } and validBasicAppDefinition

  private val complyWithResidencyRules: Validator[AppDefinition] =
    isTrue("AppDefinition must contain persistent volumes and define residency") { app =>
      !(app.residency.isDefined ^ app.persistentVolumes.nonEmpty)
    }

  private val complyWithResourceRoleRules: Validator[AppDefinition] =
    isTrue("""Resident apps may not define acceptedResourceRoles other than "*" (unreserved resources)""") { app =>
      def hasResidencyCompatibleRoles = app.acceptedResourceRoles.fold(true)(_ == Set(ResourceRole.Unreserved))
      !app.isResident || hasResidencyCompatibleRoles
    }

  private val containsCmdArgsOrContainer: Validator[AppDefinition] =
    isTrue("AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.") { app =>
      val cmd = app.cmd.nonEmpty
      val args = app.args.nonEmpty
      val container = app.container.exists(_ != Container.Empty)
      (cmd ^ args) || (!(cmd || args) && container)
    }

  private val complyWithMigrationAPI: Validator[AppDefinition] =
    isTrue("DCOS_PACKAGE_FRAMEWORK_NAME and DCOS_MIGRATION_API_PATH must be defined" +
      " when using DCOS_MIGRATION_API_VERSION") { app =>
      val understandsMigrationProtocol = app.labels.get(AppDefinition.Labels.DcosMigrationApiVersion).exists(_.nonEmpty)

      // if the api version IS NOT set, we're ok
      // if the api version IS set, we expect to see a valid version, a frameworkName and a path
      def compliesWithMigrationApi =
        app.labels.get(AppDefinition.Labels.DcosMigrationApiVersion).fold(true) { apiVersion =>
          apiVersion == "v1" &&
            app.labels.get(AppDefinition.Labels.DcosPackageFrameworkName).exists(_.nonEmpty) &&
            app.labels.get(AppDefinition.Labels.DcosMigrationApiPath).exists(_.nonEmpty)
        }

      !understandsMigrationProtocol || (understandsMigrationProtocol && compliesWithMigrationApi)
    }

  private val complyWithSingleInstanceLabelRules: Validator[AppDefinition] =
    isTrue("Single instance app may only have one instance") { app =>
      (!app.isSingleInstance) || (app.instances <= 1)
    }

  private val complyWithReadinessCheckRules: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app.readinessChecks.size should be <= 1
    app.readinessChecks is every(ReadinessCheck.readinessCheckValidator(app))
  }

  private val complyWithUpgradeStrategyRules: Validator[AppDefinition] = validator[AppDefinition] { appDef =>
    (appDef.isSingleInstance is false) or (appDef.upgradeStrategy is UpgradeStrategy.validForSingleInstanceApps)
    (appDef.isResident is false) or (appDef.upgradeStrategy is UpgradeStrategy.validForResidentTasks)
  }

  private def portIndexIsValid(hostPortsIndices: Range): Validator[HealthCheck] =
    isTrue("Health check port indices must address an element of the ports array or container port mappings.") { hc =>
      hc.protocol == Protocol.COMMAND || (hc.portIndex match {
        case Some(idx) => hostPortsIndices contains idx
        case None      => hostPortsIndices.length == 1 && hostPortsIndices.head == 0
      })
    }

  def residentUpdateIsValid(from: AppDefinition): Validator[AppDefinition] = {
    val changeNoVolumes =
      isTrue[AppDefinition]("Persistent volumes can not be changed!") { to =>
        val fromVolumes = from.persistentVolumes
        val toVolumes = to.persistentVolumes
        def sameSize = fromVolumes.size == toVolumes.size
        def noChange = from.persistentVolumes.forall { fromVolume =>
          toVolumes.find(_.containerPath == fromVolume.containerPath).contains(fromVolume)
        }
        sameSize && noChange
      }

    val changeNoResources =
      isTrue[AppDefinition]("Resident Tasks may not change resource requirements!") { to =>
        from.cpus == to.cpus &&
          from.mem == to.mem &&
          from.disk == to.disk &&
          from.portDefinitions == to.portDefinitions
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
