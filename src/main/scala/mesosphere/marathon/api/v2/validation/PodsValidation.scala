package mesosphere.marathon
package api.v2.validation

// scalastyle:off

import com.wix.accord._
import com.wix.accord.combinators.GeneralPurposeCombinators
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.validation.RunSpecValidator
import mesosphere.marathon.raml._
import mesosphere.marathon.state.{PathId, ResourceRole, Role, RootGroup}
import mesosphere.marathon.util.{RoleSettings, SemanticVersion}
// scalastyle:on

/**
  * Defines implicit validation for pods
  */
trait PodsValidation extends GeneralPurposeCombinators {
  import EnvVarValidation._
  import NameValidation._
  import NetworkValidation._
  import PodsValidationMessages._
  import SchedulingValidation._
  import SecretValidation._
  import Validation._

  private val resourceValidator = validator[Resources] { resource =>
    resource.cpus should be >= 0.0
    resource.mem should be >= 0.0
    resource.disk should be >= 0.0
    resource.gpus should be >= 0
  }

  private def httpHealthCheckValidator(endpoints: Seq[Endpoint]) =
    validator[HttpCheck] { hc =>
      hc.endpoint.length is between(1, 63)
      hc.endpoint should matchRegexFully(NamePattern)
      hc.endpoint is isTrue("contained in the container endpoints") { endpoint =>
        endpoints.exists(_.name == endpoint)
      }
      hc.path.map(_.length).getOrElse(1) is between(1, 1024)
    }

  private def tcpHealthCheckValidator(endpoints: Seq[Endpoint]) =
    validator[TcpCheck] { hc =>
      hc.endpoint.length is between(1, 63)
      hc.endpoint should matchRegexFully(NamePattern)
      hc.endpoint is isTrue("contained in the container endpoints") { endpoint =>
        endpoints.exists(_.name == endpoint)
      }
    }

  private def commandCheckValidator(mesosMasterVersion: SemanticVersion) =
    new Validator[CommandCheck] {
      override def apply(v1: CommandCheck): Result =
        if (mesosMasterVersion >= PodsValidation.MinCommandCheckMesosVersion) {
          v1.command match {
            case ShellCommand(shell) =>
              (shell.length should be > 0)(shell.length)
            case ArgvCommand(argv) =>
              (argv.size should be > 0)(argv.size)
          }
        } else {
          Failure(Set(RuleViolation(v1, s"Mesos Master ($mesosMasterVersion) does not support Command Health Checks")))
        }
    }

  private def healthCheckValidator(endpoints: Seq[Endpoint], mesosMasterVersion: SemanticVersion) =
    validator[HealthCheck] { hc =>
      hc.gracePeriodSeconds should be >= 0
      hc.intervalSeconds should be >= 0
      hc.timeoutSeconds should be < hc.intervalSeconds
      hc.maxConsecutiveFailures should be >= 0
      hc.timeoutSeconds should be >= 0
      hc.delaySeconds should be >= 0
      hc.http is optional(httpHealthCheckValidator(endpoints))
      hc.tcp is optional(tcpHealthCheckValidator(endpoints))
      hc.exec is optional(commandCheckValidator(mesosMasterVersion))
      hc is isTrue("Only one of http, tcp, or command may be specified") { hc =>
        Seq(hc.http.isDefined, hc.tcp.isDefined, hc.exec.isDefined).count(identity) == 1
      }
    }

  private def endpointValidator(networks: Seq[Network]) = {
    val networkNamess = networks.flatMap(_.name)
    val hostPortRequiresNetworkName = isTrue[Endpoint](NetworkNameRequiredForMultipleContainerNetworks) { endpoint =>
      endpoint.hostPort.isEmpty || endpoint.networkNames.length == 1
    }

    val normalValidation = validator[Endpoint] { endpoint =>
      endpoint.networkNames is every(oneOf(networkNamess: _*))

      // host-mode networking implies that containerPort is disallowed
      endpoint.containerPort is isTrue("is not allowed when using host-mode networking") { cp =>
        if (networks.exists(_.mode == NetworkMode.Host)) cp.isEmpty
        else true
      }

      // container-mode networking implies that containerPort is required
      endpoint.containerPort is isTrue("is required when using container-mode networking") { cp =>
        if (networks.exists(_.mode == NetworkMode.Container)) cp.nonEmpty
        else true
      }

      // protocol is an optional field, so we really don't need to validate that is empty/non-empty
      // but we should validate that it only contains distinct items
      endpoint.protocol is isTrue("Duplicate protocols within the same endpoint are not allowed") { proto =>
        proto == proto.distinct
      }
    }

    normalValidation and implied(networks.count(_.mode == NetworkMode.Container) > 1)(hostPortRequiresNetworkName)
  }

  private def imageValidator(enabledFeatures: Set[String], secrets: Map[String, SecretDef]): Validator[Image] =
    new Validator[Image] {
      override def apply(image: Image): Result = {
        val dockerImageValidator: Validator[Image] = validator[Image] { image =>
          image.pullConfig is empty or featureEnabled(enabledFeatures, Features.SECRETS)
          image.pullConfig is optional(
            isTrue("pullConfig.secret must refer to an existing secret")(config => secrets.contains(config.secret))
          )
        }

        image.kind match {
          case ImageType.Docker => validate(image)(dockerImageValidator)
        }
      }
    }

  private def volumeMountValidator(volumes: Seq[PodVolume]): Validator[VolumeMount] =
    validator[VolumeMount] { volumeMount => // linter:ignore:UnusedParameter
      volumeMount.name.length is between(1, 63)
      volumeMount.name should matchRegexFully(NamePattern)
      volumeMount.mountPath.length is between(1, 1024)
      volumeMount.name is isTrue("Referenced Volume in VolumeMount should exist") { name =>
        volumeNames(volumes).contains(name)
      }
    }

  private def secretVolumesValidator(secrets: Map[String, SecretDef]): Validator[PodSecretVolume] =
    validator[PodSecretVolume] { vol =>
      vol.secret is isTrue(SecretVolumeMustReferenceSecret) {
        secrets.contains(_)
      }
    }

  private val artifactValidator = validator[Artifact] { artifact =>
    artifact.uri.length is between(1, 1024)
    artifact.destPath.map(_.length).getOrElse(1) is between(1, 1024)
  }

  private def containerValidator(pod: Pod, enabledFeatures: Set[String], mesosMasterVersion: SemanticVersion): Validator[PodContainer] =
    validator[PodContainer] { container =>
      container.name is notEqualTo(Task.Id.Names.anonymousContainer)
      container.resources is resourceValidator
      container.resourceLimits is optional(AppValidation.validResourceLimits(container.resources.cpus, container.resources.mem))
      container.endpoints is every(endpointValidator(pod.networks))
      container.image is optional(imageValidator(enabledFeatures, pod.secrets))
      container.environment is envValidator(strictNameValidation = false, pod.secrets, enabledFeatures)
      container.healthCheck is optional(healthCheckValidator(container.endpoints, mesosMasterVersion))
      container.volumeMounts is every(volumeMountValidator(pod.volumes))
      container.artifacts is every(artifactValidator)
      container.linuxInfo is optional(state.LinuxInfo.validLinuxInfoForContainerRaml)
      if (pod.legacySharedCgroups.exists(identity)) {
        container.resourceLimits is isTrue("resourceLimits cannot be defined if legacySharedCgroups is enabled") { limits =>
          limits.isEmpty
        }
      }
    }

  private def volumeValidator(containers: Seq[PodContainer]): Validator[PodVolume] =
    isTrue[PodVolume]("volume must be referenced by at least one container") { v =>
      containers.exists(_.volumeMounts.exists(_.name == volumeName(v)))
    }

  private val fixedPodScalingPolicyValidator = validator[FixedPodScalingPolicy] { f =>
    f.instances should be >= 0
  }

  private val scalingValidator: Validator[PodScalingPolicy] = new Validator[PodScalingPolicy] {
    override def apply(v1: PodScalingPolicy): Result =
      v1 match {
        case fsf: FixedPodScalingPolicy => fixedPodScalingPolicyValidator(fsf)
        case _ => Failure(Set(RuleViolation(v1, "Not a fixed scaling policy")))
      }
  }

  private val endpointNamesUnique: Validator[Pod] = isTrue(EndpointNamesMustBeUnique) { pod: Pod =>
    val names = pod.containers.flatMap(_.endpoints.map(_.name))
    names.distinct.size == names.size
  }

  private val endpointContainerPortsUnique: Validator[Pod] = isTrue(ContainerPortsMustBeUnique) { pod: Pod =>
    val containerPorts = pod.containers.flatMap(_.endpoints.flatMap(_.containerPort)).filter(_ != 0)
    containerPorts.distinct.size == containerPorts.size
  }

  private val endpointHostPortsUnique: Validator[Pod] = isTrue(HostPortsMustBeUnique) { pod: Pod =>
    val hostPorts = pod.containers.flatMap(_.endpoints.flatMap(_.hostPort)).filter(_ != 0)
    hostPorts.distinct.size == hostPorts.size
  }

  // When https://github.com/wix/accord/issues/120 is resolved, we can inline this expression again
  private def podSecretVolumes(pod: Pod) =
    pod.volumes.collect { case sv: PodSecretVolume => sv }
  private def podPersistentVolumes(pod: Pod) =
    pod.volumes.collect { case pv: PodPersistentVolume => pv }
  private def podAcceptedResourceRoles(pod: Pod) =
    pod.scheduling.flatMap(_.placement.map(_.acceptedResourceRoles)).getOrElse(Seq.empty).toSet

  private val haveUnreachableDisabledForResidentPods: Validator[Pod] =
    isTrue[Pod]("unreachableStrategy must be disabled for pods with persistent volumes") { pod =>
      if (podPersistentVolumes(pod).isEmpty)
        true
      else {
        val unreachableStrategy = pod.scheduling.flatMap(_.unreachableStrategy)
        unreachableStrategy.isEmpty || unreachableStrategy == Some(UnreachableDisabled())
      }
    }

  private def haveValidAcceptedResourceRoles: Validator[Pod] =
    validator[Pod] { pod =>
      (podAcceptedResourceRoles(pod) as "acceptedResourceRoles" is empty or valid(
        ResourceRole.validAcceptedResourceRoles("pod", podPersistentVolumes(pod).nonEmpty)
      ))
    }

  def podValidator(config: MarathonConf, mesosMasterVersion: Option[SemanticVersion] = Some(SemanticVersion.zero)): Validator[Pod] =
    podValidator(config.availableFeatures, mesosMasterVersion.getOrElse(SemanticVersion.zero), config.defaultNetworkName.toOption)

  def podValidator(enabledFeatures: Set[String], mesosMasterVersion: SemanticVersion, defaultNetworkName: Option[String]): Validator[Pod] =
    validator[Pod] { pod =>
      PathId(pod.id) as "id" is valid and PathId.absolutePathValidator and PathId.nonEmptyPath
      pod.user is optional(notEmpty)
      pod.environment is envValidator(strictNameValidation = false, pod.secrets, enabledFeatures)
      podSecretVolumes(pod) is empty or featureEnabled(enabledFeatures, Features.SECRETS)
      podSecretVolumes(pod) is empty or every(secretVolumesValidator(pod.secrets))
      pod.volumes is every(volumeValidator(pod.containers)) and isTrue(VolumeNamesMustBeUnique) { volumes: Seq[PodVolume] =>
        val names = volumeNames(volumes)
        names.distinct.size == names.size
      }
      pod.containers is notEmpty and every(containerValidator(pod, enabledFeatures, mesosMasterVersion))
      pod.containers is isTrue(ContainerNamesMustBeUnique) { containers: Seq[PodContainer] =>
        val names = pod.containers.map(_.name)
        names.distinct.size == names.size
      }
      pod.secrets is empty or (secretValidator and featureEnabled(enabledFeatures, Features.SECRETS))
      pod.networks is ramlNetworksValidator
      pod.networks is defaultNetworkNameValidator(() => defaultNetworkName)
      pod.scheduling is optional(schedulingValidator)
      pod.scaling is optional(scalingValidator)
      pod is endpointNamesUnique and endpointContainerPortsUnique and endpointHostPortsUnique
      pod should complyWithPodUpgradeStrategyRules
      pod should haveUnreachableDisabledForResidentPods
      pod should haveValidAcceptedResourceRoles
      pod.linuxInfo is optional(state.LinuxInfo.validLinuxInfoForPodRaml)
    }

  def podDefValidator(pluginManager: PluginManager, roleSettings: RoleSettings): Validator[PodDefinition] =
    validator[PodDefinition] { podDef =>
      podDef is pluginValidators(pluginManager)
      podDef is validPodDefinitionWithRoleEnforcement(roleSettings)
    }

  def validPodDefinitionWithRoleEnforcement(roleEnforcement: RoleSettings): Validator[PodDefinition] =
    validator[PodDefinition] { pod =>
      pod.role is in(roleEnforcement.validRoles)
      // DO NOT MERGE THESE TWO similar if blocks! Wix Accord macros do weird stuff otherwise.
      if (pod.isResident) {
        pod.role is isTrue(s"Resident pods cannot have the role ${ResourceRole.Unreserved}") { role: Role =>
          !role.equals(ResourceRole.Unreserved)
        }
      }
      if (pod.isResident) {
        pod.role is isTrue((role: Role) => RoleSettings.residentRoleChangeWarningMessage(roleEnforcement.previousRole.get, role)) {
          role: Role =>
            roleEnforcement.previousRole.map(_.equals(role) || roleEnforcement.forceRoleUpdate).getOrElse(true)
        }
      }
      pod.acceptedResourceRoles is valid(ResourceRole.validForRole(pod.role))
    }

  private def volumeNames(volumes: Seq[PodVolume]): Seq[String] = volumes.map(volumeName)
  private def volumeName(volume: PodVolume): String =
    volume match {
      case PodEphemeralVolume(name) => name
      case PodHostVolume(name, _) => name
      case PodSecretVolume(name, _) => name
      case PodPersistentVolume(name, _) => name
    }

  private def pluginValidators(implicit pluginManager: PluginManager): Validator[PodDefinition] =
    new Validator[PodDefinition] {
      override def apply(pod: PodDefinition): Result = {
        val plugins = pluginManager.plugins[RunSpecValidator]
        new And(plugins: _*).apply(pod)
      }
    }

  def residentUpdateIsValid(from: PodDefinition): Validator[PodDefinition] = {
    val changeNoVolumes =
      isTrue[PodDefinition]("persistent volumes cannot be updated") { to =>
        val fromVolumes = from.persistentVolumes
        val toVolumes = to.persistentVolumes
        def sameSize = fromVolumes.size == toVolumes.size
        def noVolumeChange =
          fromVolumes.forall { fromVolume =>
            toVolumes.find(_.name == fromVolume.name).contains(fromVolume)
          }
        sameSize && noVolumeChange
      }

    val changeNoCpuResource =
      isTrue[PodDefinition](CpusPersistentVolumes) { to =>
        from.resources.cpus == to.resources.cpus
      }

    val changeNoMemResource =
      isTrue[PodDefinition](MemPersistentVolumes) { to =>
        from.resources.mem == to.resources.mem
      }

    val changeNoDiskResource =
      isTrue[PodDefinition](DiskPersistentVolumes) { to =>
        from.resources.disk == to.resources.disk
      }

    val changeNoGpuResource =
      isTrue[PodDefinition](GpusPersistentVolumes) { to =>
        from.resources.gpus == to.resources.gpus
      }

    val changeNoHostPort =
      isTrue[PodDefinition](HostPortsPersistentVolumes) { to =>
        val fromHostPorts = from.containers.flatMap(_.endpoints.flatMap(_.hostPort)).toSet
        val toHostPorts = to.containers.flatMap(_.endpoints.flatMap(_.hostPort)).toSet
        fromHostPorts == toHostPorts
      }

    validator[PodDefinition] { pod =>
      pod should changeNoVolumes
      pod should changeNoCpuResource
      pod should changeNoMemResource
      pod should changeNoDiskResource
      pod should changeNoGpuResource
      pod should changeNoHostPort
      pod.upgradeStrategy is state.UpgradeStrategy.validForResidentTasks
    }
  }

  def updateIsValid(from: RootGroup): Validator[PodDefinition] =
    new Validator[PodDefinition] {
      override def apply(pod: PodDefinition): Result = {
        from.pod(pod.id) match {
          case (Some(last)) if last.isResident || pod.isResident => residentUpdateIsValid(last)(pod)
          case _ => Success
        }
      }
    }
}

object PodsValidation extends PodsValidation {
  val MinCommandCheckMesosVersion = SemanticVersion(1, 3, 0)
}

object PodsValidationMessages {
  val EndpointNamesMustBeUnique = "endpoint names nust be unique across all containers"
  val ContainerPortsMustBeUnique = "container ports must be unique across all containers"
  val HostPortsMustBeUnique = "host ports must be unique across all containers"
  val VolumeNamesMustBeUnique = "volume names must be unique"
  val ContainerNamesMustBeUnique = "container names must be unique"
  val SecretVolumeMustReferenceSecret = "volume.secret must refer to an existing secret"
  val CpusPersistentVolumes = "cpus cannot be updated if a pod has persistent volumes"
  val MemPersistentVolumes = "mem cannot be updated if a pod has persistent volumes"
  val DiskPersistentVolumes = "disk cannot be updated if a pod has persistent volumes"
  val GpusPersistentVolumes = "gpus cannot be updated if a pod has persistent volumes"
  val HostPortsPersistentVolumes = "host ports cannot be updated if a pod has persistent volumes"
  // Note: we should keep this in sync with AppValidationMessages
  val NetworkNameRequiredForMultipleContainerNetworks =
    "networkNames must be a single item list when hostPort is specified and more than 1 container network is defined"
}
