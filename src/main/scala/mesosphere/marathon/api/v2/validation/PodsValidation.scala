package mesosphere.marathon
package api.v2.validation

// scalastyle:off

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ ArgvCommand, Artifact, CommandHealthCheck, Endpoint, FixedPodScalingPolicy, HealthCheck, HttpHealthCheck, Image, ImageType, Lifecycle, Network, NetworkMode, Pod, PodContainer, PodScalingPolicy, Resources, ShellCommand, TcpHealthCheck, Volume, VolumeMount }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.util.SemanticVersion

// scalastyle:on

/**
  * Defines implicit validation for pods
  */
@SuppressWarnings(Array("all")) // wix breaks stuff
trait PodsValidation {
  import EnvVarValidation._
  import NameValidation._
  import NetworkValidation._
  import SchedulingValidation._
  import SecretValidation._
  import Validation._

  val resourceValidator = validator[Resources] { resource =>
    resource.cpus should be >= 0.0
    resource.mem should be >= 0.0
    resource.disk should be >= 0.0
    resource.gpus should be >= 0
  }

  def httpHealthCheckValidator(endpoints: Seq[Endpoint]) = validator[HttpHealthCheck] { hc =>
    hc.endpoint.length is between(1, 63)
    hc.endpoint should matchRegexFully(NamePattern)
    hc.endpoint is isTrue("contained in the container endpoints") { endpoint =>
      endpoints.exists(_.name == endpoint)
    }
    hc.path.map(_.length).getOrElse(1) is between(1, 1024)
  }

  def tcpHealthCheckValidator(endpoints: Seq[Endpoint]) = validator[TcpHealthCheck] { hc =>
    hc.endpoint.length is between(1, 63)
    hc.endpoint should matchRegexFully(NamePattern)
    hc.endpoint is isTrue("contained in the container endpoints") { endpoint =>
      endpoints.exists(_.name == endpoint)
    }
  }

  def commandCheckValidator(mesosMasterVersion: SemanticVersion) = new Validator[CommandHealthCheck] {
    override def apply(v1: CommandHealthCheck): Result = if (mesosMasterVersion >= PodsValidation.MinCommandCheckMesosVersion) {
      v1.command match {
        case ShellCommand(shell) =>
          (shell.length should be > 0)(shell.length)
        case ArgvCommand(argv) =>
          (argv.size should be > 0)(argv.size)
      }
    } else {
      Failure(Set(RuleViolation(v1, s"Mesos Master ($mesosMasterVersion) does not support Command Health Checks", None)))
    }
  }

  def healthCheckValidator(endpoints: Seq[Endpoint], mesosMasterVersion: SemanticVersion) = validator[HealthCheck] { hc =>
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

  def endpointValidator(networks: Seq[Network]) = validator[Endpoint] { endpoint =>
    // TODO RAML-generated rules should catch these simple things
    endpoint.name.length is between(1, 63)
    endpoint.name should matchRegexFully(NamePattern)
    endpoint.containerPort.getOrElse(1) is between(1, 65535)
    endpoint.hostPort.getOrElse(0) is between(0, 65535)

    // host-mode networking implies that hostPort is required
    endpoint.hostPort is isTrue("is required when using host-mode networking") { hp =>
      if (networks.exists(_.mode == NetworkMode.Host)) hp.nonEmpty
      else true
    }

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
    endpoint.protocol is isTrue ("Duplicate protocols within the same endpoint are not allowed") { proto =>
      proto == proto.distinct
    }
  }

  val imageValidator = validator[Image] { image =>
    image.id.length is between(1, 1024)
  }

  def volumeMountValidator(volumes: Seq[Volume]): Validator[VolumeMount] = validator[VolumeMount] { volumeMount => // linter:ignore:UnusedParameter
    volumeMount.name.length is between(1, 63)
    volumeMount.name should matchRegexFully(NamePattern)
    volumeMount.mountPath.length is between(1, 1024)
    volumeMount.name is isTrue("Referenced Volume in VolumeMount should exist") { name =>
      volumes.exists(_.name == name)
    }
  }

  val artifactValidator = validator[Artifact] { artifact =>
    artifact.uri.length is between(1, 1024)
    artifact.destPath.map(_.length).getOrElse(1) is between(1, 1024)
  }

  val lifeCycleValidator = validator[Lifecycle] { lc =>
    lc.killGracePeriodSeconds.getOrElse(0.0) should be > 0.0
  }

  def containerValidator(pod: Pod, enabledFeatures: Set[String], mesosMasterVersion: SemanticVersion): Validator[PodContainer] =
    validator[PodContainer] { container =>
      container.resources is valid(resourceValidator)
      container.endpoints is every(endpointValidator(pod.networks))
      container.image.getOrElse(Image(ImageType.Docker, "abc")) is valid(imageValidator)
      container.environment is envValidator(strictNameValidation = true, pod.secrets, enabledFeatures)
      container.healthCheck is optional(healthCheckValidator(container.endpoints, mesosMasterVersion))
      container.volumeMounts is every(volumeMountValidator(pod.volumes))
      container.artifacts is every(artifactValidator)
    }

  def volumeValidator(containers: Seq[PodContainer]): Validator[Volume] = validator[Volume] { volume =>
    volume.host is optional(notEmpty)
  } and isTrue[Volume]("volume must be referenced by at least one container") { v =>
    containers.exists(_.volumeMounts.exists(_.name == v.name))
  }

  val fixedPodScalingPolicyValidator = validator[FixedPodScalingPolicy] { f =>
    f.instances should be >= 0
  }

  val scalingValidator: Validator[PodScalingPolicy] = new Validator[PodScalingPolicy] {
    override def apply(v1: PodScalingPolicy): Result = v1 match {
      case fsf: FixedPodScalingPolicy => fixedPodScalingPolicyValidator(fsf)
      case _ => Failure(Set(RuleViolation(v1, "Not a fixed scaling policy", None)))
    }
  }

  val endpointNamesUnique: Validator[Pod] = isTrue("Endpoint names are unique") { pod: Pod =>
    val names = pod.containers.flatMap(_.endpoints.map(_.name))
    names.distinct.size == names.size
  }

  val endpointContainerPortsUnique: Validator[Pod] = isTrue("Container ports are unique") { pod: Pod =>
    val containerPorts = pod.containers.flatMap(_.endpoints.flatMap(_.containerPort))
    containerPorts.distinct.size == containerPorts.size
  }

  val endpointHostPortsUnique: Validator[Pod] = isTrue("Host ports are unique") { pod: Pod =>
    val hostPorts = pod.containers.flatMap(_.endpoints.flatMap(_.hostPort)).filter(_ != 0)
    hostPorts.distinct.size == hostPorts.size
  }

  def podDefValidator(enabledFeatures: Set[String], mesosMasterVersion: SemanticVersion): Validator[Pod] = validator[Pod] { pod =>
    PathId(pod.id) as "id" is valid and PathId.absolutePathValidator and PathId.nonEmptyPath
    pod.user is optional(notEmpty)
    pod.environment is envValidator(strictNameValidation = true, pod.secrets, enabledFeatures)
    pod.volumes is every(volumeValidator(pod.containers)) and isTrue("volume names are unique") { volumes: Seq[Volume] =>
      val names = volumes.map(_.name)
      names.distinct.size == names.size
    }
    pod.containers is notEmpty and every(containerValidator(pod, enabledFeatures, mesosMasterVersion))
    pod.containers is isTrue("container names are unique") { containers: Seq[PodContainer] =>
      val names = pod.containers.map(_.name)
      names.distinct.size == names.size
    }
    pod.secrets is empty or (valid(secretValidator) and featureEnabled(enabledFeatures, Features.SECRETS))
    pod.networks is valid(ramlNetworksValidator)
    pod.scheduling is optional(schedulingValidator)
    pod.scaling is optional(scalingValidator)
    pod is endpointNamesUnique and endpointContainerPortsUnique and endpointHostPortsUnique
  }
}

object PodsValidation extends PodsValidation {
  // TODO: Change this value when mesos supports command checks for pods.
  val MinCommandCheckMesosVersion = SemanticVersion(Int.MaxValue, Int.MaxValue, Int.MaxValue)
}
