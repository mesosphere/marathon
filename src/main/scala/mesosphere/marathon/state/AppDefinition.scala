package mesosphere.marathon
package state

import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.combinators.GeneralPurposeCombinators
import com.wix.accord.dsl._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.serialization._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.plugin.validation.RunSpecValidator
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.AppDefinition.Labels
import mesosphere.marathon.state.Container.{ Docker, MesosAppC, MesosDocker }
import mesosphere.marathon.state.VersionInfo._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.TaskBuilder
import mesosphere.mesos.protos.{ Resource, ScalarResource }
import org.apache.mesos.{ Protos => mesos }

import scala.concurrent.duration._
import scala.util.Try

case class AppDefinition(

  id: PathId,

  override val cmd: Option[String] = AppDefinition.DefaultCmd,

  override val args: Seq[String] = AppDefinition.DefaultArgs,

  user: Option[String] = AppDefinition.DefaultUser,

  env: Map[String, EnvVarValue] = AppDefinition.DefaultEnv,

  instances: Int = AppDefinition.DefaultInstances,

  resources: Resources = AppDefinition.DefaultResources,

  executor: String = AppDefinition.DefaultExecutor,

  constraints: Set[Constraint] = AppDefinition.DefaultConstraints,

  fetch: Seq[FetchUri] = AppDefinition.DefaultFetch,

  storeUrls: Seq[String] = AppDefinition.DefaultStoreUrls,

  portDefinitions: Seq[PortDefinition] = AppDefinition.DefaultPortDefinitions,

  requirePorts: Boolean = AppDefinition.DefaultRequirePorts,

  backoffStrategy: BackoffStrategy = AppDefinition.DefaultBackoffStrategy,

  override val container: Option[Container] = AppDefinition.DefaultContainer,

  healthChecks: Set[_ <: HealthCheck] = AppDefinition.DefaultHealthChecks,

  readinessChecks: Seq[ReadinessCheck] = AppDefinition.DefaultReadinessChecks,

  taskKillGracePeriod: Option[FiniteDuration] = AppDefinition.DefaultTaskKillGracePeriod,

  dependencies: Set[PathId] = AppDefinition.DefaultDependencies,

  upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,

  labels: Map[String, String] = Labels.Default,

  acceptedResourceRoles: Set[String] = AppDefinition.DefaultAcceptedResourceRoles,

  ipAddress: Option[IpAddress] = None,

  versionInfo: VersionInfo = VersionInfo.OnlyVersion(Timestamp.now()),

  override val residency: Option[Residency] = AppDefinition.DefaultResidency,

  secrets: Map[String, Secret] = AppDefinition.DefaultSecrets,

  override val unreachableStrategy: UnreachableStrategy = AppDefinition.DefaultUnreachableStrategy,

  override val killSelection: KillSelection = KillSelection.DefaultKillSelection) extends RunSpec
    with plugin.ApplicationSpec with MarathonState[Protos.ServiceDefinition, AppDefinition] {

  import mesosphere.mesos.protos.Implicits._

  require(
    ipAddress.isEmpty || portDefinitions.isEmpty,
    s"IP address ($ipAddress) and ports ($portDefinitions) are not allowed at the same time")

  val portNumbers: Seq[Int] = portDefinitions.map(_.port)

  val isResident: Boolean = residency.isDefined

  override val version: Timestamp = versionInfo.version

  override val isSingleInstance: Boolean = labels.get(Labels.SingleInstanceApp).contains("true")
  override val volumes: Seq[Volume] = container.fold(Seq.empty[Volume])(_.volumes)
  override val persistentVolumes: Seq[PersistentVolume] = volumes.collect { case vol: PersistentVolume => vol }
  override val externalVolumes: Seq[ExternalVolume] = volumes.collect { case vol: ExternalVolume => vol }

  override val diskForPersistentVolumes: Double = persistentVolumes.map(_.persistent.size).sum.toDouble

  def toProto: Protos.ServiceDefinition = {
    val commandInfo = TaskBuilder.commandInfo(
      runSpec = this,
      taskId = None,
      host = None,
      hostPorts = Seq.empty,
      envPrefix = None
    )
    val cpusResource = ScalarResource(Resource.CPUS, resources.cpus)
    val memResource = ScalarResource(Resource.MEM, resources.mem)
    val diskResource = ScalarResource(Resource.DISK, resources.disk)
    val gpusResource = ScalarResource(Resource.GPUS, resources.gpus.toDouble)
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
      .addAllPortDefinitions(portDefinitions.map(PortDefinitionSerializer.toProto))
      .setRequirePorts(requirePorts)
      .setBackoff(backoffStrategy.backoff.toMillis)
      .setBackoffFactor(backoffStrategy.factor)
      .setMaxLaunchDelay(backoffStrategy.maxLaunchDelay.toMillis)
      .setExecutor(executor)
      .addAllConstraints(constraints)
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(gpusResource)
      .addAllHealthChecks(healthChecks.map(_.toProto)(collection.breakOut))
      .setUpgradeStrategy(upgradeStrategy.toProto)
      .addAllDependencies(dependencies.map(_.toString))
      .addAllStoreUrls(storeUrls)
      .addAllLabels(appLabels)
      .addAllSecrets(secrets.map(SecretsSerializer.toProto))
      .addAllEnvVarReferences(env.flatMap(EnvVarRefSerializer.toProto))
      .setUnreachableStrategy(unreachableStrategy.toProto)
      .setKillSelection(killSelection.toProto)

    ipAddress.foreach { ip => builder.setIpAddress(ip.toProto) }
    container.foreach { c => builder.setContainer(ContainerSerializer.toProto(c)) }
    readinessChecks.foreach { r => builder.addReadinessCheckDefinition(ReadinessCheckSerializer.toProto(r)) }
    taskKillGracePeriod.foreach { t => builder.setTaskKillGracePeriod(t.toMillis) }

    if (acceptedResourceRoles.nonEmpty) {
      val roles = Protos.ResourceRoles.newBuilder()
      roles.addAllRole(acceptedResourceRoles)
      builder.setAcceptedResourceRoles(roles)
    }

    builder.setVersion(version.toString)
    versionInfo match {
      case fullInfo: FullVersionInfo =>
        builder.setLastScalingAt(fullInfo.lastScalingAt.millis)
        builder.setLastConfigChangeAt(fullInfo.lastConfigChangeAt.millis)
      case _ => // ignore
    }

    residency.foreach { r => builder.setResidency(ResidencySerializer.toProto(r)) }

    builder.build
  }

  override def withInstances(instances: Int): RunSpec = copy(instances = instances)

  def mergeFromProto(proto: Protos.ServiceDefinition): AppDefinition = {
    val envMap: Map[String, EnvVarValue] = EnvVarValue(
      proto.getCmd.getEnvironment.getVariablesList.map {
        v => v.getName -> v.getValue
      }(collection.breakOut))

    val envRefs: Map[String, EnvVarValue] =
      proto.getEnvVarReferencesList.flatMap(EnvVarRefSerializer.fromProto)(collection.breakOut)

    val resourcesMap: Map[String, Double] =
      proto.getResourcesList.map {
        r => r.getName -> (r.getScalar.getValue: Double)
      }(collection.breakOut)

    val argsOption = proto.getCmd.getArgumentsList.toSeq

    //Precondition: either args or command is defined
    val commandOption =
      if (argsOption.isEmpty && proto.getCmd.hasValue && proto.getCmd.getValue.nonEmpty)
        Some(proto.getCmd.getValue)
      else None

    val containerOption = if (proto.hasContainer) Some(ContainerSerializer.fromProto(proto.getContainer)) else None

    val acceptedResourceRoles = proto.getAcceptedResourceRoles.getRoleList.toSet

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
      if (proto.getPortsCount > 0) PortDefinitions(proto.getPortsList.map(_.intValue)(collection.breakOut): _*)
      else proto.getPortDefinitionsList.map(PortDefinitionSerializer.fromProto).to[Seq]

    val unreachableStrategy =
      if (proto.hasUnreachableStrategy)
        UnreachableStrategy.fromProto(proto.getUnreachableStrategy)
      else
        UnreachableStrategy.default(residencyOption.isDefined)

    AppDefinition(
      id = PathId(proto.getId),
      user = if (proto.getCmd.hasUser) Some(proto.getCmd.getUser) else None,
      cmd = commandOption,
      args = argsOption,
      executor = proto.getExecutor,
      instances = proto.getInstances,
      portDefinitions = portDefinitions,
      requirePorts = proto.getRequirePorts,
      backoffStrategy = BackoffStrategy(
        backoff = proto.getBackoff.milliseconds,
        factor = proto.getBackoffFactor,
        maxLaunchDelay = proto.getMaxLaunchDelay.milliseconds),
      constraints = proto.getConstraintsList.toSet,
      acceptedResourceRoles = acceptedResourceRoles,
      resources = Resources(
        cpus = resourcesMap.getOrElse(Resource.CPUS, this.resources.cpus),
        mem = resourcesMap.getOrElse(Resource.MEM, this.resources.mem),
        disk = resourcesMap.getOrElse(Resource.DISK, this.resources.disk),
        gpus = resourcesMap.getOrElse(Resource.GPUS, this.resources.gpus.toDouble).toInt
      ),
      env = envMap ++ envRefs,
      fetch = proto.getCmd.getUrisList.map(FetchUri.fromProto)(collection.breakOut),
      storeUrls = proto.getStoreUrlsList.toSeq,
      container = containerOption,
      healthChecks = proto.getHealthChecksList.map(HealthCheck.fromProto).toSet,
      readinessChecks =
        proto.getReadinessCheckDefinitionList.map(ReadinessCheckSerializer.fromProto)(collection.breakOut),
      taskKillGracePeriod = if (proto.hasTaskKillGracePeriod) Some(proto.getTaskKillGracePeriod.milliseconds)
      else None,
      labels = proto.getLabelsList.map { p => p.getKey -> p.getValue }(collection.breakOut),
      versionInfo = versionInfoFromProto,
      upgradeStrategy =
        if (proto.hasUpgradeStrategy) UpgradeStrategy.fromProto(proto.getUpgradeStrategy)
        else UpgradeStrategy.empty,
      dependencies = proto.getDependenciesList.map(PathId(_))(collection.breakOut),
      ipAddress = ipAddressOption,
      residency = residencyOption,
      secrets = proto.getSecretsList.map(SecretsSerializer.fromProto)(collection.breakOut),
      unreachableStrategy = unreachableStrategy,
      killSelection = KillSelection.fromProto(proto.getKillSelection)
    )
  }

  val hostPorts: Seq[Option[Int]] =
    container.withFilter(_.portMappings.nonEmpty).map(_.hostPorts).getOrElse(portNumbers.map(Some(_)))

  val servicePorts: Seq[Int] =
    container.withFilter(_.portMappings.nonEmpty).map(_.servicePorts).getOrElse(portNumbers)

  private val portIndices: Range = hostPorts.indices

  val hasDynamicServicePorts: Boolean = servicePorts.contains(AppDefinition.RandomPortValue)

  val networkModeBridge: Boolean =
    container.exists(_.docker.exists(_.network.contains(mesos.ContainerInfo.DockerInfo.Network.BRIDGE)))

  val networkModeUser: Boolean =
    container.exists(_.docker.exists(_.network.contains(mesos.ContainerInfo.DockerInfo.Network.USER)))

  def mergeFromProto(bytes: Array[Byte]): AppDefinition = {
    val proto = Protos.ServiceDefinition.parseFrom(bytes)
    mergeFromProto(proto)
  }

  /**
    * Returns whether this is a scaling change only.
    */
  def isOnlyScaleChange(to: RunSpec): Boolean = !isUpgrade(to) && (instances != to.instances)

  /**
    * True if the given app definition is a change to the current one in terms of runtime characteristics
    * of all deployed tasks of the current app, otherwise false.
    */
  def isUpgrade(to: RunSpec): Boolean = to match {
    case to: AppDefinition =>
      id == to.id && {
        cmd != to.cmd ||
          args != to.args ||
          user != to.user ||
          env != to.env ||
          resources != to.resources ||
          executor != to.executor ||
          constraints != to.constraints ||
          fetch != to.fetch ||
          storeUrls != to.storeUrls ||
          portDefinitions != to.portDefinitions ||
          requirePorts != to.requirePorts ||
          backoffStrategy != to.backoffStrategy ||
          container != to.container ||
          healthChecks != to.healthChecks ||
          taskKillGracePeriod != to.taskKillGracePeriod ||
          dependencies != to.dependencies ||
          upgradeStrategy != to.upgradeStrategy ||
          labels != to.labels ||
          acceptedResourceRoles != to.acceptedResourceRoles ||
          ipAddress != to.ipAddress ||
          readinessChecks != to.readinessChecks ||
          residency != to.residency ||
          secrets != to.secrets ||
          unreachableStrategy != to.unreachableStrategy ||
          killSelection != to.killSelection
      }
    case _ =>
      // A validation rule will ensure, this can not happen
      throw new IllegalStateException("Can't change app to pod")
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
  def needsRestart(to: RunSpec): Boolean = this.versionInfo != to.versionInfo || isUpgrade(to)

  def withCanonizedIds(base: PathId = PathId.empty): AppDefinition = {
    val baseId = id.canonicalPath(base)
    copy(id = baseId, dependencies = dependencies.map(_.canonicalPath(baseId)))
  }

  val portNames: Seq[String] = {
    def fromDiscoveryInfo = ipAddress.map(_.discoveryInfo.ports.map(_.name).toList).getOrElse(Seq.empty)
    def fromPortMappings = container.map(_.portMappings.flatMap(_.name)).getOrElse(Seq.empty)
    def fromPortDefinitions = portDefinitions.flatMap(_.name)

    if (networkModeBridge || networkModeUser) fromPortMappings
    else if (ipAddress.isDefined) fromDiscoveryInfo
    else fromPortDefinitions
  }
}

@SuppressWarnings(Array("IsInstanceOf")) // doesn't work well in the validation macros?!
object AppDefinition extends GeneralPurposeCombinators {

  type AppKey = PathId

  val RandomPortValue: Int = 0
  val RandomPortDefinition: PortDefinition = PortDefinition(RandomPortValue, "tcp", None, Map.empty[String, String])

  // App defaults
  val DefaultId = PathId.empty

  val DefaultCmd = Option.empty[String]

  val DefaultArgs = Seq.empty[String]

  val DefaultUser = Option.empty[String]

  val DefaultEnv = Map.empty[String, EnvVarValue]

  val DefaultInstances: Int = 1

  val DefaultCpus: Double = 1.0

  val DefaultMem: Double = 128.0

  val DefaultDisk: Double = 0.0

  val DefaultGpus: Int = 0

  val DefaultResources = Resources(cpus = DefaultCpus, mem = DefaultMem, disk = DefaultDisk, gpus = DefaultGpus)

  val DefaultExecutor: String = ""

  val DefaultConstraints = Set.empty[Constraint]

  val DefaultUris = Seq.empty[String]

  val DefaultFetch: Seq[FetchUri] = FetchUri.empty

  val DefaultStoreUrls = Seq.empty[String]

  val DefaultPortDefinitions: Seq[PortDefinition] = Seq(PortDefinition(RandomPortValue, "tcp", Some("default"), Map.empty))

  val DefaultRequirePorts: Boolean = false

  val DefaultBackoff: FiniteDuration = 1.second

  val DefaultBackoffFactor = 1.15

  val DefaultMaxLaunchDelay: FiniteDuration = 1.hour

  val DefaultBackoffStrategy = BackoffStrategy(DefaultBackoff, DefaultMaxLaunchDelay, DefaultBackoffFactor)

  val DefaultContainer = Option.empty[Container]

  val DefaultHealthChecks = Set.empty[HealthCheck]

  val DefaultReadinessChecks = Seq.empty[ReadinessCheck]

  val DefaultTaskKillGracePeriod = Option.empty[FiniteDuration]

  val DefaultDependencies = Set.empty[PathId]

  val DefaultUpgradeStrategy: UpgradeStrategy = UpgradeStrategy.empty

  val DefaultSecrets = Map.empty[String, Secret]

  val DefaultUnreachableStrategy = UnreachableStrategy.default(resident = false)

  object Labels {
    val Default = Map.empty[String, String]

    val DcosMigrationApiPath = "DCOS_MIGRATION_API_PATH"
    val DcosMigrationApiVersion = "DCOS_MIGRATION_API_VERSION"
    val DcosPackageFrameworkName = "DCOS_PACKAGE_FRAMEWORK_NAME"
    val SingleInstanceApp = "MARATHON_SINGLE_INSTANCE_APP"
  }

  /**
    * This default is only used in tests
    */
  val DefaultAcceptedResourceRoles = Set.empty[String]

  val DefaultResidency = Option.empty[Residency]

  def fromProto(proto: Protos.ServiceDefinition): AppDefinition =
    AppDefinition(id = DefaultId).mergeFromProto(proto)

  /**
    * We cannot validate HealthChecks here, because it would break backwards compatibility in weird ways.
    * If users had already one invalid app definition, each deployment would cause a complete revalidation of
    * the root group including the invalid one.
    * Until the user changed all invalid apps, the user would get weird validation
    * errors for every deployment potentially unrelated to the deployed apps.
    */
  def validAppDefinition(
    enabledFeatures: Set[String])(implicit pluginManager: PluginManager): Validator[AppDefinition] =
    validator[AppDefinition] { app =>
      app.id is valid and PathId.absolutePathValidator and PathId.nonEmptyPath
      app.dependencies is valid
    } and validBasicAppDefinition(enabledFeatures) and pluginValidators

  /**
    * Validator for apps, which are being part of a group.
    *
    * @param base Path of the parent group.
    * @return
    */
  def validNestedAppDefinition(base: PathId, enabledFeatures: Set[String]): Validator[AppDefinition] =
    validator[AppDefinition] { app =>
      app.id is PathId.validPathWithBase(base)
    } and validBasicAppDefinition(enabledFeatures)

  private def pluginValidators(implicit pluginManager: PluginManager): Validator[AppDefinition] =
    new Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        val plugins = pluginManager.plugins[RunSpecValidator]
        new And(plugins: _*)(app)
      }
    }

  private def complyWithIpAddressRules(app: AppDefinition): Validator[IpAddress] = {
    import mesos.ContainerInfo.DockerInfo.Network.{ BRIDGE, USER }
    isTrue[IpAddress]("ipAddress/discovery is not allowed for Docker containers using BRIDGE or USER networks") { ip =>
      !(ip.discoveryInfo.nonEmpty &&
        app.container.exists(_.docker.exists(_.network.exists(Set(BRIDGE, USER)))))
    }
  }

  private val complyWithResidencyRules: Validator[AppDefinition] =
    isTrue("AppDefinition must contain persistent volumes and define residency") { app =>
      !(app.residency.isDefined ^ app.persistentVolumes.nonEmpty)
    }

  private val containsCmdArgsOrContainer: Validator[AppDefinition] =
    isTrue("AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.") { app =>
      val cmd = app.cmd.nonEmpty
      val args = app.args.nonEmpty
      val container = app.container.exists {
        case _: MesosDocker => true
        case _: MesosAppC => true
        case _: Container.Docker => true
        case _ => false
      }
      (cmd ^ args) || (!(cmd && args) && container)
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

  private def complyWithGpuRules(enabledFeatures: Set[String]): Validator[AppDefinition] =
    conditional[AppDefinition](_.resources.gpus > 0) {
      isTrue[AppDefinition]("GPU resources only work with the Mesos containerizer") { app =>
        app.container match {
          case Some(_: Docker) => false
          case _ => true
        }
      } and featureEnabled(enabledFeatures, Features.GPU_RESOURCES)
    }

  val complyWithConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import Constraint.Operator._
    override def apply(c: Constraint): Result = {
      if (!c.hasField || !c.hasOperator) {
        Failure(Set(RuleViolation(c, "Missing field and operator", None)))
      } else {
        c.getOperator match {
          case UNIQUE =>
            if (c.hasValue && c.getValue.nonEmpty) {
              Failure(Set(RuleViolation(c, "Value specified but not used", None)))
            } else {
              Success
            }
          case CLUSTER =>
            if (c.hasValue && c.getValue.nonEmpty) {
              Success
            } else {
              Failure(Set(RuleViolation(c, "Missing value", None)))
            }
          case GROUP_BY =>
            if (!c.hasValue || (c.hasValue && c.getValue.nonEmpty && Try(c.getValue.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was specified but is not a number",
                Some("GROUP_BY may either have no value or an integer value"))))
            }
          case LIKE | UNLIKE =>
            if (c.hasValue) {
              Try(Pattern.compile(c.getValue)) match {
                case scala.util.Success(_) =>
                  Success
                case scala.util.Failure(e) =>
                  Failure(Set(RuleViolation(
                    c,
                    s"'${c.getValue}' is not a valid regular expression",
                    Some(s"${c.getValue}\n${e.getMessage}"))))
              }
            } else {
              Failure(Set(RuleViolation(c, "A regular expression value must be provided", None)))
            }
          case MAX_PER =>
            if (c.hasValue && c.getValue.nonEmpty && Try(c.getValue.toInt).isSuccess) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was not specified or is not a number",
                Some("MAX_PER must have an integer value"))))
            }
          case _ =>
            Failure(Set(
              RuleViolation(c, "Operator must be one of UNIQUE, CLUSTER, GROUP_BY, LIKE, MAX_PER or UNLIKE", None)))
        }
      }
    }
  }

  private val haveAtMostOneMesosHealthCheck: Validator[AppDefinition] =
    isTrue[AppDefinition]("AppDefinition can contain at most one Mesos health check") { appDef =>
      // Previous versions of Marathon allowed saving an app definition with more than one command health check, and
      // we don't want to make them invalid
      (appDef.healthChecks.count(_.isInstanceOf[MesosHealthCheck]) -
        appDef.healthChecks.count(_.isInstanceOf[MesosCommandHealthCheck])) <= 1
    }

  private[state] val requireUnreachableDisabledForResidentTasks =
    isTrue[AppDefinition]("unreachableStrategy must be disabled for resident tasks") { app =>
      if (app.isResident)
        app.unreachableStrategy == UnreachableDisabled
      else
        true
    }

  private def validBasicAppDefinition(enabledFeatures: Set[String]) = validator[AppDefinition] { appDef =>
    appDef.upgradeStrategy is valid
    appDef.container.each is valid(Container.validContainer(enabledFeatures))
    appDef.storeUrls is every(urlIsValid)
    appDef.portDefinitions is PortDefinitions.portDefinitionsValidator
    appDef.executor should matchRegexFully("^(//cmd)|(/?[^/]+(/[^/]+)*)|$")
    appDef is containsCmdArgsOrContainer
    appDef.healthChecks is every(portIndexIsValid(appDef.portIndices))
    appDef must haveAtMostOneMesosHealthCheck
    appDef.instances should be >= 0
    appDef.fetch is every(fetchUriIsValid)
    appDef.resources.mem as "mem" should be >= 0.0
    appDef.resources.cpus as "cpus" should be >= 0.0
    appDef.instances should be >= 0
    appDef.resources.disk as "disk" should be >= 0.0
    appDef.resources.gpus as "gpus" should be >= 0
    appDef.secrets is valid(Secret.secretsValidator)
    appDef.secrets is empty or featureEnabled(enabledFeatures, Features.SECRETS)
    appDef.env is valid(EnvVarValue.envValidator)
    appDef.acceptedResourceRoles is empty or valid(ResourceRole.validAcceptedResourceRoles(appDef.isResident))
    appDef must complyWithGpuRules(enabledFeatures)
    appDef must complyWithMigrationAPI
    appDef must complyWithReadinessCheckRules
    appDef must complyWithResidencyRules
    appDef must complyWithSingleInstanceLabelRules
    appDef must complyWithUpgradeStrategyRules
    appDef should requireUnreachableDisabledForResidentTasks
    appDef.constraints.each must complyWithConstraintRules
    appDef.ipAddress must optional(complyWithIpAddressRules(appDef))
    appDef.unreachableStrategy is valid
  } and ExternalVolumes.validApp and EnvVarValue.validApp

  @SuppressWarnings(Array("TraversableHead"))
  private def portIndexIsValid(hostPortsIndices: Range): Validator[HealthCheck] =
    isTrue("Health check port indices must address an element of the ports array or container port mappings.") {
      case hc: HealthCheckWithPort =>
        hc.portIndex match {
          case Some(PortReference.ByIndex(idx)) => hostPortsIndices.contains(idx)
          case Some(PortReference.ByName(name)) => false // TODO(jdef) support port name as an index
          case None => hc.port.nonEmpty || (hostPortsIndices.length == 1 && hostPortsIndices.head == 0)
        }
      case _ => true
    }

  @SuppressWarnings(Array("ComparingFloatingPointTypes"))
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
        from.resources.cpus == to.resources.cpus &&
          from.resources.mem == to.resources.mem &&
          from.resources.disk == to.resources.disk &&
          from.resources.gpus == to.resources.gpus &&
          from.hostPorts.flatten.toSet == to.hostPorts.flatten.toSet &&
          from.requirePorts == to.requirePorts
      }

    validator[AppDefinition] { app =>
      app should changeNoVolumes
      app should changeNoResources
      app.upgradeStrategy is UpgradeStrategy.validForResidentTasks
    }
  }

  def updateIsValid(from: RootGroup): Validator[AppDefinition] = {
    new Validator[AppDefinition] {
      override def apply(app: AppDefinition): Result = {
        from.transitiveAppsById.get(app.id) match {
          case (Some(last)) if last.isResident || app.isResident => residentUpdateIsValid(last)(app)
          case _ => Success
        }
      }
    }
  }
}
