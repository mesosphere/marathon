package mesosphere.marathon.state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._
import org.apache.mesos.{ Protos => Mesos }
import scala.collection.immutable.Seq

// TODO: trait Container and specializations?
// Current implementation with type defaulting to DOCKER and docker to NONE makes no sense
case class Container(
    `type`: Mesos.ContainerInfo.Type = Mesos.ContainerInfo.Type.DOCKER,
    volumes: Seq[Volume] = Nil,
    docker: Option[Container.Docker] = None) {

  import Container._

  def portMappings: Option[Seq[Docker.PortMapping]] = {
    import Mesos.ContainerInfo.DockerInfo.Network
    for {
      d <- docker
      n <- d.network if n == Network.BRIDGE || n == Network.USER
      pms <- d.portMappings
    } yield pms
  }

  def hostPorts: Option[Seq[Option[Int]]] =
    for (pms <- portMappings) yield pms.map(_.hostPort)

  def servicePorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.servicePort)
}

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

    def withDefaultPortMappings(
      image: String = "",
      network: Option[Mesos.ContainerInfo.DockerInfo.Network] = None,
      portMappings: Option[Seq[Docker.PortMapping]] = None,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Nil,
      forcePullImage: Boolean = false): Docker = Docker(
      image = image,
      network = network,
      portMappings = network match {
        case Some(networkMode) if networkMode == Mesos.ContainerInfo.DockerInfo.Network.BRIDGE =>
          portMappings.map(_.map { m =>
            m match {
              // backwards compat: when in BRIDGE mode, missing host ports default to zero
              case PortMapping(x, None, y, z, w, a) => PortMapping(x, Some(PortMapping.HostPortDefault), y, z, w, a)
              case _ => m
            }
          })
        case _ => portMappings
      },
      privileged = privileged,
      parameters = parameters,
      forcePullImage = forcePullImage)

    /**
      * @param containerPort The container port to expose
      * @param hostPort      The host port to bind
      * @param servicePort   The well-known port for this service
      * @param protocol      Layer 4 protocol to expose (i.e. "tcp", "udp" or "udp,tcp" for both).
      * @param name          Name of the service hosted on this port.
      * @param labels        This can be used to decorate the message with metadata to be
      *                      interpreted by external applications such as firewalls.
      */
    case class PortMapping(
      containerPort: Int = AppDefinition.RandomPortValue,
      hostPort: Option[Int] = None, // defaults to HostPortDefault for BRIDGE mode, None for USER mode
      servicePort: Int = AppDefinition.RandomPortValue,
      protocol: String = "tcp",
      name: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String])

    object PortMapping {
      val TCP = "tcp"
      val UDP = "udp"

      val HostPortDefault = AppDefinition.RandomPortValue // HostPortDefault only applies when in BRIDGE mode

      implicit val uniqueProtocols: Validator[Iterable[String]] =
        isTrue[Iterable[String]]("protocols must be unique.") { protocols =>
          protocols.size == protocols.toSet.size
        }

      implicit val portMappingValidator = validator[PortMapping] { portMapping =>
        portMapping.protocol.split(',').toIterable is uniqueProtocols and every(oneOf(TCP, UDP))
        portMapping.containerPort should be >= 0
        portMapping.hostPort.each should be >= 0
        portMapping.servicePort should be >= 0
        portMapping.name is optional(matchRegexFully(PortAssignment.PortNamePattern))
      }

      def networkHostPortValidator(d: Docker): Validator[PortMapping] =
        isTrue[PortMapping]("hostPort is required for BRIDGE mode.") { pm =>
          d.network match {
            case Some(Mesos.ContainerInfo.DockerInfo.Network.BRIDGE) => pm.hostPort.isDefined
            case _ => true
          }
        }
    }

    object PortMappings {
      val portMappingsValidator: Validator[Seq[PortMapping]] = validator[Seq[PortMapping]] { portMappings =>
        portMappings is every(valid)
        portMappings is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
      }

      def validForDocker(d: Docker): Validator[Seq[PortMapping]] = validator[Seq[PortMapping]] { pm =>
        pm is every(valid (PortMapping.networkHostPortValidator(d)))
      }
    }

    implicit val dockerValidator = validator[Docker] { docker =>
      docker.image is notEmpty
      docker.portMappings is optional(PortMappings.portMappingsValidator and PortMappings.validForDocker(docker))
    }
  }

  // We need validation based on the container type, but don't have dedicated classes. Therefore this approach manually
  // delegates validation to the matching validator
  implicit val validContainer: Validator[Container] = {
    val validGeneralContainer = validator[Container] { container =>
      container.volumes is every(valid)
    }

    val validDockerContainer: Validator[Container] = validator[Container] { container =>
      container.docker is notEmpty
      container.docker.each is valid
    }

    val validMesosContainer: Validator[Container] = validator[Container] { container =>
      container.docker is empty
    }

    new Validator[Container] {
      override def apply(c: Container): Result = c.`type` match {
        case Mesos.ContainerInfo.Type.MESOS => validate(c)(validMesosContainer)
        case Mesos.ContainerInfo.Type.DOCKER => validate(c)(validDockerContainer)
        case _ => Failure(Set(RuleViolation(c.`type`, "unknown", None)))
      }
    } and validGeneralContainer
  }
}
