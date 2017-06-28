package mesosphere.marathon
package api.v2.validation

import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.AppNormalization
import mesosphere.marathon.api.v2.Validation.{ featureEnabled, _ }
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.raml._
import mesosphere.marathon.state.{ AppDefinition, PathId, ResourceRole }
import mesosphere.marathon.stream.Implicits._

import scala.util.Try

trait AppValidation {
  import ArtifactValidation._
  import EnvVarValidation._
  import NetworkValidation._
  import PathId.{ empty => _, _ }
  import SchedulingValidation._
  import SecretValidation._

  val portDefinitionsValidator: Validator[Seq[PortDefinition]] = validator[Seq[PortDefinition]] {
    portDefinitions =>
      portDefinitions is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
      portDefinitions is elementsAreUniqueBy(_.port, "Ports must be unique.",
        filter = { (port: Int) => port != AppDefinition.RandomPortValue })
  }

  val portMappingsIndependentOfNetworks = validator[Seq[ContainerPortMapping]] { portMappings =>
    portMappings is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
  }

  private def portMappingNetworkNameValidator(networkNames: Seq[String]) = validator[ContainerPortMapping] { portMapping =>
    portMapping.networkNames is every(oneOf(networkNames: _*))
  }

  private def portMappingIsCompatibleWithNetworks(networks: Seq[Network]): Validator[ContainerPortMapping] = {
    val hostPortRequiresNetworkName = isTrue[ContainerPortMapping](
      AppValidationMessages.NetworkNameRequiredForMultipleContainerNetworks) { mapping =>
      mapping.hostPort.isEmpty || mapping.networkNames.length == 1
    }
    implied(networks.count(_.mode == NetworkMode.Container) > 1)(hostPortRequiresNetworkName)
  }

  def portMappingsValidator(networks: Seq[Network]): Validator[Seq[ContainerPortMapping]] = validator { portMappings =>
    portMappings is portMappingsIndependentOfNetworks
    portMappings is every(portMappingIsCompatibleWithNetworks(networks))
    portMappings is every(portMappingNetworkNameValidator(networks.flatMap(_.name)))
  }

  def dockerDockerContainerValidator(networks: Seq[Network]): Validator[Container] = {
    val validDockerEngineSpec: Validator[DockerContainer] = validator[DockerContainer] { docker =>
      docker.image is notEmpty
      docker.pullConfig is isTrue("pullConfig is not supported with Docker containerizer")(_.isEmpty)
      docker.portMappings is valid(optional(portMappingsValidator(networks)))
    }
    validator { (container: Container) =>
      container.docker is definedAnd(validDockerEngineSpec)
    }
  }

  def mesosDockerContainerValidator(enabledFeatures: Set[String], secrets: Map[String, SecretDef]): Validator[Container] = {
    val validPullConfigSpec: Validator[DockerPullConfig] = validator[DockerPullConfig] { pullConfig =>
      pullConfig is isTrue("pullConfig.secret must refer to an existing secret")(
        config => secrets.contains(config.secret))
    }
    val validMesosEngineSpec: Validator[DockerContainer] = validator[DockerContainer] { docker =>
      docker.image is notEmpty
      docker.pullConfig is empty or featureEnabled(enabledFeatures, Features.SECRETS)
      docker.pullConfig is optional(valid(validPullConfigSpec))
    }
    validator { (container: Container) =>
      container.docker is valid(definedAnd(validMesosEngineSpec))
    }
  }

  val mesosAppcContainerValidator: Validator[Container] = {
    val prefix = "sha512-"

    val validId: Validator[String] =
      isTrue[String](s"id must begin with '$prefix',") { id =>
        id.startsWith(prefix)
      } and isTrue[String](s"id must contain non-empty digest after '$prefix'.") { id =>
        id.length > prefix.length
      }

    val validMesosEngineSpec: Validator[AppCContainer] = validator[AppCContainer] { appc =>
      appc.image is notEmpty
      appc.id is optional(validId)
    }
    validator{ (container: Container) =>
      container.appc is valid(definedAnd(validMesosEngineSpec))
    }
  }

  val mesosImagelessContainerValidator: Validator[Container] =
    // placeholder, there is no additional validation to do for a non-image-based mesos container
    new NullSafeValidator[Container](_ => true, _ => Failure(Set.empty))

  val validOldContainerAPI: Validator[Container] = new Validator[Container] {

    val forDockerContainerizer: Validator[Container] = {
      val oldDockerDockerContainerAPI: Validator[DockerContainer] = validator[DockerContainer] { docker =>
        docker.credential is empty // credentials aren't supported this way anymore
      }
      validator[Container] { container =>
        container.docker is optional(valid(oldDockerDockerContainerAPI))
      }
    }
    val forMesosContainerizer: Validator[Container] = {
      val oldMesosDockerContainerAPI: Validator[DockerContainer] = validator[DockerContainer] { docker =>
        docker.credential is empty // credentials aren't supported this way anymore
        docker.network is empty
        docker.parameters is empty
        docker.portMappings is empty
      }
      validator[Container] { container =>
        container.docker is optional(valid(oldMesosDockerContainerAPI))
      }
    }
    override def apply(container: Container): Result = {
      (container.docker, container.appc, container.`type`) match {
        case (Some(_), None, EngineType.Docker) => validate(container)(forDockerContainerizer)
        case (Some(_), None, EngineType.Mesos) => validate(container)(forMesosContainerizer)
        case _ => Success // canonical validation picks up where we leave off
      }
    }
  }

  def validContainer(enabledFeatures: Set[String], networks: Seq[Network], secrets: Map[String, SecretDef]): Validator[Container] = {
    def volumesValidator(container: Container): Validator[Seq[AppVolume]] =
      isTrue("Volume names must be unique") { (vols: Seq[AppVolume]) =>
        val names: Seq[String] = vols.collect{ case v: AppExternalVolume => v.external.name }.flatten
        names.distinct.size == names.size
      } and every(valid(validVolume(container, enabledFeatures, secrets)))

    val validGeneralContainer: Validator[Container] = validator[Container] { container =>
      container.portMappings is optional(portMappingsValidator(networks))
      container.volumes is volumesValidator(container)
      container.volumes.filterPF { case _: AppSecretVolume => true } is empty or featureEnabled(enabledFeatures, Features.SECRETS)
    }

    val mesosContainerImageValidator = new Validator[Container] {
      override def apply(container: Container): Result = {
        (container.docker, container.appc, container.`type`) match {
          case (Some(_), None, EngineType.Mesos) => validate(container)(mesosDockerContainerValidator(enabledFeatures, secrets))
          case (None, Some(_), EngineType.Mesos) => validate(container)(mesosAppcContainerValidator)
          case (None, None, EngineType.Mesos) => validate(container)(mesosImagelessContainerValidator)
          case _ => Failure(Set(RuleViolation(container, "mesos containers should specify, at most, a single image type", None)))
        }
      }
    }

    forAll(
      validGeneralContainer,
      { c: Container => c.`type` == EngineType.Docker } -> dockerDockerContainerValidator(networks),
      { c: Container => c.`type` == EngineType.Mesos } -> mesosContainerImageValidator
    )
  }

  def validVolume(container: Container, enabledFeatures: Set[String], secrets: Map[String, SecretDef]): Validator[AppVolume] = new Validator[AppVolume] {
    import state.PathPatterns._
    val validHostVolume = validator[AppDockerVolume] { v =>
      v.containerPath is valid(notEmpty)
      v.hostPath is valid(notEmpty)
    }
    val validPersistentVolume = {
      val notHaveConstraintsOnRoot = isTrue[PersistentVolume](
        "Constraints on root volumes are not supported") { info =>
          if (info.`type`.forall(_ == PersistentVolumeType.Root)) // default is Root, see AppConversion
            info.constraints.isEmpty
          else
            true
        }

      val meetMaxSizeConstraint = isTrue[PersistentVolume]("Only mount volumes can have maxSize") { info =>
        info.`type`.contains(PersistentVolumeType.Mount) || info.maxSize.isEmpty
      }

      val haveProperlyOrderedMaxSize = isTrue[PersistentVolume]("Max size must be larger than size") { info =>
        info.maxSize.forall(_ > info.size)
      }

      val complyWithVolumeConstraintRules: Validator[Seq[String]] = new Validator[Seq[String]] {
        override def apply(c: Seq[String]): Result = {
          import Protos.Constraint.Operator._
          (c.headOption, c.lift(1), c.lift(2)) match {
            case (None, None, _) =>
              Failure(Set(RuleViolation(c, "Missing field and operator", None)))
            case (Some("path"), Some(op), Some(value)) =>
              Try(Protos.Constraint.Operator.valueOf(op)).toOption.map {
                case LIKE | UNLIKE =>
                  Try(Pattern.compile(value)).toOption.map(_ => Success).getOrElse(
                    Failure(Set(RuleViolation(c, "Invalid regular expression", Some(value))))
                  )
                case _ =>
                  Failure(Set(
                    RuleViolation(c, "Operator must be one of LIKE, UNLIKE", None)))
              }.getOrElse(
                Failure(Set(
                  RuleViolation(c, s"unknown constraint operator $op", None)))
              )
            case _ =>
              Failure(Set(RuleViolation(c, s"Unsupported constraint ${c.mkString(",")}", None)))
          }
        }
      }

      val validPersistentInfo = validator[PersistentVolume] { info =>
        info.size should be > 0L
        info.constraints.each must complyWithVolumeConstraintRules
      } and meetMaxSizeConstraint and notHaveConstraintsOnRoot and haveProperlyOrderedMaxSize

      validator[AppPersistentVolume] { v =>
        v.containerPath is valid(notEqualTo("") and notOneOf(DotPaths: _*))
        v.containerPath is valid(matchRegexWithFailureMessage(NoSlashesPattern, "value must not contain \"/\""))
        v.mode is equalTo(ReadMode.Rw) // see AppConversion, default is RW
        v.persistent is valid(validPersistentInfo)
      }
    }
    val validExternalVolume: Validator[AppExternalVolume] = {
      import state.OptionLabelPatterns._
      val validOptions = validator[Map[String, String]] { option =>
        option.keys.each should matchRegex(OptionKeyRegex)
      }
      val validExternalInfo: Validator[ExternalVolume] = validator[ExternalVolume] { info =>
        info.name is valid(definedAnd(matchRegex(LabelRegex)))
        info.provider is valid(definedAnd(matchRegex(LabelRegex)))
        info.options is validOptions
      }

      forAll(
        validator[AppExternalVolume] { v =>
          v.containerPath is valid(notEmpty)
          v.external is valid(validExternalInfo)
        },
        { v: AppExternalVolume => v.external.provider.nonEmpty } -> ExternalVolumes.validRamlVolume(container),
        featureEnabled[AppVolume](enabledFeatures, Features.EXTERNAL_VOLUMES)
      )
    }
    val validSecretVolume: Validator[AppSecretVolume] = {
      isTrue("volume.secret must refer to an existing secret")(
        vol => secrets.contains(vol.secret))
    }
    override def apply(v: AppVolume): Result = {
      v match {
        case v: AppDockerVolume => validate(v)(validHostVolume)
        case v: AppPersistentVolume => validate(v)(validPersistentVolume)
        case v: AppExternalVolume => validate(v)(validExternalVolume)
        case v: AppSecretVolume => validate(v)(validSecretVolume) // Validate that the secret reference is valid
        case _ => Failure(Set(RuleViolation(v, "Unknown app volume type", None)))
      }
    }
  }

  def readinessCheckValidator(app: App): Validator[ReadinessCheck] = {
    // we expect that the deprecated API has already been translated into canonical form
    def namesFromDefinitions = app.portDefinitions.fold(Set.empty[String])(_.flatMap(_.name)(collection.breakOut))
    def portNames = app.container.flatMap(_.portMappings).fold(namesFromDefinitions)(_.flatMap(_.name)(collection.breakOut))
    def portNameExists = isTrue[String]{ name: String => s"No port definition reference for portName $name" } { name =>
      portNames.contains(name)
    }
    validator[ReadinessCheck] { rc =>
      rc.portName is valid(portNameExists)
      rc.timeoutSeconds should be < rc.intervalSeconds
    }
  }

  /**
    * all validation that touches deprecated app-update API fields goes in here
    */
  def validateOldAppUpdateAPI: Validator[AppUpdate] = forAll(
    validator[AppUpdate] { update =>
      update.container is optional(valid(validOldContainerAPI))
      update.container.flatMap(_.docker.flatMap(_.portMappings)) is optional(portMappingsIndependentOfNetworks)
      update.ipAddress is optional(isTrue(
        "ipAddress/discovery is not allowed for Docker containers") { (ipAddress: IpAddress) =>
          !(update.container.exists(c => c.`type` == EngineType.Docker) && ipAddress.discovery.nonEmpty)
        })
      update.uris is optional(every(api.v2.Validation.uriIsValid) and isTrue(
        "may not be set in conjunction with fetch") { (uris: Seq[String]) =>
          !(uris.nonEmpty && update.fetch.fold(false)(_.nonEmpty))
        })
    },
    isTrue("ports must be unique") { update =>
      val withoutRandom = update.ports.fold(Seq.empty[Int])(_.filterNot(_ == AppDefinition.RandomPortValue))
      withoutRandom.distinct.size == withoutRandom.size
    },
    isTrue("cannot specify both an IP address and port") { update =>
      val appWithoutPorts = update.ports.fold(true)(_.isEmpty) && update.portDefinitions.fold(true)(_.isEmpty)
      appWithoutPorts || update.ipAddress.isEmpty
    },
    isTrue("cannot specify both ports and port definitions") { update =>
      val portDefinitionsIsEquivalentToPorts = update.portDefinitions.map(_.map(_.port)) == update.ports
      portDefinitionsIsEquivalentToPorts || update.ports.isEmpty || update.portDefinitions.isEmpty
    },
    isTrue("must not specify both networks and ipAddress") { update =>
      !(update.ipAddress.nonEmpty && update.networks.fold(false)(_.nonEmpty))
    },
    isTrue("must not specify both container.docker.network and networks") { update =>
      !(update.container.exists(_.docker.exists(_.network.nonEmpty)) && update.networks.nonEmpty)
    }
  )

  def validateCanonicalAppUpdateAPI(enabledFeatures: Set[String], normalizationConfig: AppNormalization.Config): Validator[AppUpdate] = forAll(
    validator[AppUpdate] { update =>
      update.id.map(PathId(_)) as "id" is optional(valid)
      update.dependencies.map(_.map(PathId(_))) as "dependencies" is optional(every(valid))
      update.env is optional(envValidator(strictNameValidation = false, update.secrets.getOrElse(Map.empty), enabledFeatures))
      update.secrets is empty or featureEnabled(enabledFeatures, Features.SECRETS)
      update.secrets is optional(featureEnabledImplies(enabledFeatures, Features.SECRETS)(every(secretEntryValidator)))
      update.fetch is optional(every(valid))
      update.portDefinitions is optional(portDefinitionsValidator)
      update.container is optional(valid(validContainer(enabledFeatures, update.networks.getOrElse(Nil), update.secrets.getOrElse(Map.empty))))
      update.acceptedResourceRoles is valid(optional(ResourceRole.validAcceptedResourceRoles(update.residency.isDefined) and notEmpty))
    },
    isTrue("must not be root")(!_.id.fold(false)(PathId(_).isRoot)),
    isTrue("must not be an empty string")(_.cmd.forall { s => s.length() > 1 }),
    isTrue("portMappings are not allowed with host-networking") { update =>
      !(update.networks.exists(_.exists(_.mode == NetworkMode.Host)) && update.container.exists(_.portMappings.exists(_.nonEmpty)))
    },
    isTrue("portDefinitions are only allowed with host-networking") { update =>
      !(update.networks.exists(_.exists(_.mode != NetworkMode.Host)) && update.portDefinitions.exists(_.nonEmpty))
    },
    isTrue(AppValidationMessages.NetworkNameMustBeSpecified) { update =>
      update.networks.forall(n => n.forall(c => c.mode != NetworkMode.Container || c.name.isDefined || normalizationConfig.defaultNetworkName.isDefined))
    },
    isTrue("The 'version' field may only be combined with the 'id' field.") { update =>
      def onlyVersionOrIdSet: Boolean = update.productIterator.forall {
        case x: Some[Any] => x == update.version || x == update.id // linter:ignore UnlikelyEquality
        case _ => true
      }
      update.version.isEmpty || onlyVersionOrIdSet
    }
  )

  /**
    * all validation that touches deprecated app API fields goes in here
    */
  val validateOldAppAPI: Validator[App] = forAll(
    validator[App] { app =>
      app.container is optional(valid(validOldContainerAPI))
      app.container.flatMap(_.docker.flatMap(_.portMappings)) is optional(portMappingsIndependentOfNetworks)
      app.ipAddress is optional(isTrue(
        "ipAddress/discovery is not allowed for Docker containers") { (ipAddress: IpAddress) =>
          !(app.container.exists(c => c.`type` == EngineType.Docker) && ipAddress.discovery.nonEmpty)
        })
      app.uris is optional(every(api.v2.Validation.uriIsValid) and isTrue(
        "may not be set in conjunction with fetch") { (uris: Seq[String]) => !(uris.nonEmpty && app.fetch.nonEmpty) })
    },
    isTrue("must not specify both container.docker.network and networks") { app =>
      !(app.container.exists(_.docker.exists(_.network.nonEmpty)) && app.networks.nonEmpty)
    },
    isTrue("must not specify both networks and ipAddress") { app =>
      !(app.ipAddress.nonEmpty && app.networks.nonEmpty)
    },
    isTrue("ports must be unique") { (app: App) =>
      val withoutRandom: Seq[Int] = app.ports.map(_.filterNot(_ == AppDefinition.RandomPortValue)).getOrElse(Nil)
      withoutRandom.distinct.size == withoutRandom.size
    },
    isTrue("cannot specify both an IP address and port") { app =>
      def appWithoutPorts = !(app.ports.exists(_.nonEmpty) || app.portDefinitions.exists(_.nonEmpty))
      app.ipAddress.isEmpty || appWithoutPorts
    },
    isTrue("cannot specify both ports and port definitions") { app =>
      def portDefinitionsIsEquivalentToPorts = app.portDefinitions.map(_.map(_.port)) == app.ports
      app.ports.isEmpty || app.portDefinitions.isEmpty || portDefinitionsIsEquivalentToPorts
    }
  )

  def validateCanonicalAppAPI(enabledFeatures: Set[String], normalizationConfig: AppNormalization.Config): Validator[App] = forAll(
    validBasicAppDefinition(enabledFeatures),
    validator[App] { app =>
      PathId(app.id) as "id" is (PathId.pathIdValidator and PathId.absolutePathValidator and PathId.nonEmptyPath)
      app.dependencies.map(PathId(_)) as "dependencies" is every(valid)
    },
    isTrue("must not be root")(!_.id.toPath.isRoot),
    isTrue("must not be an empty string")(_.cmd.forall { s => s.length() > 1 }),
    isTrue("portMappings are not allowed with host-networking") { app =>
      !(app.networks.exists(_.mode == NetworkMode.Host) && app.container.exists(_.portMappings.exists(_.nonEmpty)))
    },
    isTrue("portDefinitions are only allowed with host-networking") { app =>
      !(app.networks.exists(_.mode != NetworkMode.Host) && app.portDefinitions.exists(_.nonEmpty))
    },
    isTrue(AppValidationMessages.NetworkNameMustBeSpecified) { app =>
      app.networks.forall(c => c.mode != NetworkMode.Container || c.name.isDefined || normalizationConfig.defaultNetworkName.isDefined)
    }
  )

  /** expects that app is already in canonical form and that someone else is (or will) handle basic app validation */
  def validNestedApp(base: PathId): Validator[App] = validator[App] { app =>
    PathId(app.id) as "id" is PathId.validPathWithBase(base)
  }

  def portIndices(app: App): Range = {
    // should be kept in sync with AppDefinition.portIndices
    app.container.withFilter(_.portMappings.nonEmpty)
      .flatMap(_.portMappings).orElse(app.portDefinitions).getOrElse(Nil).indices
  }

  /** validate most canonical API fields */
  private def validBasicAppDefinition(enabledFeatures: Set[String]): Validator[App] = validator[App] { app =>
    app.container is optional(valid(validContainer(enabledFeatures, app.networks, app.secrets)))
    app.portDefinitions is optional(portDefinitionsValidator)
    app is containsCmdArgsOrContainer
    app.healthChecks is every(portIndexIsValid(portIndices(app)))
    app must haveAtMostOneMesosHealthCheck
    app.fetch is every(valid)
    app.secrets is valid({ secrets: Map[String, SecretDef] =>
      secrets.nonEmpty
    } -> (featureEnabled(enabledFeatures, Features.SECRETS)))
    app.secrets is valid(featureEnabledImplies(enabledFeatures, Features.SECRETS)(every(secretEntryValidator)))
    app.env is envValidator(strictNameValidation = false, app.secrets, enabledFeatures)
    app.acceptedResourceRoles is valid(optional(ResourceRole.validAcceptedResourceRoles(app.residency.isDefined) and notEmpty))
    app must complyWithGpuRules(enabledFeatures)
    app must complyWithMigrationAPI
    app must complyWithReadinessCheckRules
    app must complyWithResidencyRules
    app must complyWithSingleInstanceLabelRules
    app must complyWithUpgradeStrategyRules
    app must complyWithDockerNetworkingRules
    app must requireUnreachableDisabledForResidentTasks
    app.constraints.each must complyWithAppConstraintRules
    app.networks is ramlNetworksValidator
  } and ExternalVolumes.validAppRaml

  val requireUnreachableDisabledForResidentTasks =
    conditional((app: App) => app.residency.isDefined && app.unreachableStrategy.isDefined)(
      isTrue("unreachableStrategy must be disabled for resident tasks") { app =>
        app.unreachableStrategy.collectFirst { case x: UnreachableDisabled => x }.isDefined
      }
    )

  /**
    * The Mesos docker containerizer implementation only supports a single CNI network.
    */
  val complyWithDockerNetworkingRules: Validator[App] =
    conditional((app: App) => app.container.fold(false)(_.`type` == EngineType.Docker))(
      isTrue(AppValidationMessages.DockerEngineLimitedToSingleContainerNetwork){
        _.networks.count(_.mode == NetworkMode.Container) <= 1
      }
    )

  private val complyWithResidencyRules: Validator[App] =
    isTrue("App must contain persistent volumes and define residency") { app =>
      val hasPersistentVolumes = app.container.fold(false)(_.volumes.existsAn[AppPersistentVolume])
      !(app.residency.isDefined ^ hasPersistentVolumes)
    }

  private val complyWithReadinessCheckRules: Validator[App] = validator[App] { app =>
    app.readinessChecks.size should be <= 1
    app.readinessChecks is every(readinessCheckValidator(app))
  }

  // TODO: migrate DCOS-specific things to plugins
  private val complyWithMigrationAPI: Validator[App] =
    isTrue("DCOS_PACKAGE_FRAMEWORK_NAME and DCOS_MIGRATION_API_PATH must be defined" +
      " when using DCOS_MIGRATION_API_VERSION") { app =>
      val understandsMigrationProtocol = app.labels.get(Apps.LabelDcosMigrationApiVersion).exists(_.nonEmpty)

      // if the api version IS NOT set, we're ok
      // if the api version IS set, we expect to see a valid version, a frameworkName and a path
      def compliesWithMigrationApi =
        app.labels.get(Apps.LabelDcosMigrationApiVersion).fold(true) { apiVersion =>
          apiVersion == "v1" &&
            app.labels.get(Apps.LabelDcosPackageFrameworkName).exists(_.nonEmpty) &&
            app.labels.get(Apps.LabelDcosMigrationApiPath).exists(_.nonEmpty)
        }

      !understandsMigrationProtocol || (understandsMigrationProtocol && compliesWithMigrationApi)
    }

  private def complyWithGpuRules(enabledFeatures: Set[String]): Validator[App] =
    conditional[App](_.gpus > 0) {
      isTrue[App]("GPU resources only work with the Mesos containerizer") { app =>
        !app.container.exists(_.`type` == EngineType.Docker)
      } and featureEnabled(enabledFeatures, Features.GPU_RESOURCES)
    }

  private def portIndexIsValid(hostPortsIndices: Range): Validator[AppHealthCheck] = {
    val marathonProtocols = Set(AppHealthCheckProtocol.Http, AppHealthCheckProtocol.Https, AppHealthCheckProtocol.Tcp)
    isTrue("Health check port indices must address an element of the ports array or container port mappings.") { check =>
      if (check.command.isEmpty && marathonProtocols.contains(check.protocol)) {
        check.portIndex match {
          case Some(idx) => hostPortsIndices.contains(idx)
          case _ => check.port.nonEmpty || (hostPortsIndices.length == 1 && hostPortsIndices.headOption.contains(0))
        }
      } else {
        true
      }
    }
  }

  private val haveAtMostOneMesosHealthCheck: Validator[App] = {
    val mesosProtocols = Set(
      AppHealthCheckProtocol.Command,
      AppHealthCheckProtocol.MesosHttp,
      AppHealthCheckProtocol.MesosHttps,
      AppHealthCheckProtocol.MesosTcp)

    isTrue[App]("AppDefinition can contain at most one Mesos health check") { app =>
      val mesosCommandHealthChecks = app.healthChecks.count(_.command.nonEmpty)
      val allMesosHealthChecks = app.healthChecks.count { check =>
        check.command.nonEmpty || mesosProtocols.contains(check.protocol)
      }
      // Previous versions of Marathon allowed saving an app definition with more than one command health check, and
      // we don't want to make them invalid
      allMesosHealthChecks - mesosCommandHealthChecks <= 1
    }
  }

  private val containsCmdArgsOrContainer: Validator[App] =
    isTrue("AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.") { app =>
      val cmd = app.cmd.nonEmpty
      val args = app.args.nonEmpty
      val container = app.container.exists { ct =>
        (ct.docker, ct.appc, ct.`type`) match {
          case (Some(_), None, EngineType.Docker) |
            (Some(_), None, EngineType.Mesos) |
            (None, Some(_), EngineType.Mesos) => true
          case _ => false
        }
      }
      (cmd ^ args) || (!(cmd && args) && container)
    }
}

object AppValidation extends AppValidation

object AppValidationMessages {
  // Note: we should keep this in sync with PodsValidationMessages
  val NetworkNameRequiredForMultipleContainerNetworks =
    "networkNames must be a single item list when hostPort is specified and more than 1 container network is defined"

  val DockerEngineLimitedToSingleContainerNetwork =
    "may only specify a single container network when using the Docker container engine"

  val NetworkNameMustBeSpecified = "Network name must be specified when container network is selected."
}
