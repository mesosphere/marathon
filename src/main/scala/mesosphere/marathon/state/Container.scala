package mesosphere.marathon.state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.Validation.validate
import org.apache.mesos.{ Protos => Mesos }
import scala.collection.immutable.Seq

// TODO: trait Container and specializations?
// Current implementation with type defaulting to DOCKER and docker to NONE makes no sense
case class Container(
  `type`: Mesos.ContainerInfo.Type = Mesos.ContainerInfo.Type.DOCKER,
  volumes: Seq[Volume] = Nil,
  docker: Option[Container.Docker] = None)

object Container {

  object Empty extends Container

  /**
    * Docker-specific container parameters.
    */
  case class Docker(
    image: String = "",
    network: Option[Mesos.ContainerInfo.DockerInfo.Network] = None,
    portMappings: Option[Seq[Docker.PortMapping]] = None,
    privileged: Boolean = false,
    parameters: Seq[Parameter] = Nil,
    forcePullImage: Boolean = false)

  object Docker {

    /**
      * @param containerPort The container port to expose
      * @param hostPort      The host port to bind
      * @param servicePort   The well-known port for this service
      * @param protocol      Layer 4 protocol to expose (i.e. tcp, udp).
      * @param name          Name of the service hosted on this port.
      * @param labels        This can be used to decorate the message with metadata to be
      *                      interpreted by external applications such as firewalls.
      */
    case class PortMapping(
        containerPort: Int = 0,
        hostPort: Int = 0,
        servicePort: Int = 0,
        protocol: String = "tcp",
        name: Option[String] = None,
        labels: Map[String, String] = Map.empty[String, String]) {

      require(protocol == "tcp" || protocol == "udp", "protocol can only be 'tcp' or 'udp'")
    }

    object PortMapping {
      val TCP = "tcp"
      val UDP = "udp"

      val portMappingValidator = validator[PortMapping] { portMapping =>
        portMapping.protocol is oneOf(TCP, UDP)
        portMapping.containerPort should be >= 0
        portMapping.hostPort should be >= 0
        portMapping.servicePort should be >= 0
      }
    }

    val uniquePortNames: Validator[Seq[PortMapping]] = new Validator[Seq[PortMapping]] {
      override def apply(portMappings: Seq[PortMapping]): Result = {
        val portNames = portMappings.flatMap(_.name)
        if (portNames.size == portNames.distinct.size) Success
        else Failure(Set(RuleViolation("portMappings", "Port names must be unique.", None)))
      }
    }

    implicit val dockerValidator = validator[Docker] { docker =>
      docker.image is notEmpty
      docker.portMappings is optional(every(PortMapping.portMappingValidator)) and optional(uniquePortNames)
    }
  }

  // We need validation based on the container type, but don't have dedicated classes. Therefore this approach manually
  // delegates validation to the matching validator
  implicit val containerValidator: Validator[Container] = {
    val volumeValidator: Validator[Volume] = new Validator[Volume] {
      override def apply(volume: Volume): Result = volume match {
        case pv: PersistentVolume => valid[PersistentVolume](PersistentVolume.persistentVolumeValidator).apply(pv)
        case dv: DockerVolume     => valid[DockerVolume](DockerVolume.dockerVolumeValidator).apply(dv)
      }
    }

    val dockerContainerValidator: Validator[Container] = validator[Container] { container =>
      container.docker is notEmpty
      container.docker.each is valid
      container.volumes is every(valid(volumeValidator))
    }

    val mesosContainerValidator: Validator[Container] = validator[Container] { container =>
      container.docker is empty
      container.volumes is every(valid(volumeValidator))
    }

    new Validator[Container] {
      override def apply(c: Container): Result = c.`type` match {
        case Mesos.ContainerInfo.Type.MESOS  => validate(c)(mesosContainerValidator)
        case Mesos.ContainerInfo.Type.DOCKER => validate(c)(dockerContainerValidator)
        case _                               => Failure(Set(RuleViolation(c.`type`, "unknown", None)))
      }
    }
  }

}
