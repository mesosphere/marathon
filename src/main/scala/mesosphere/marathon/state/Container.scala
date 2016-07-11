package mesosphere.marathon.state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._

import org.apache.mesos.Protos.ContainerInfo

import scala.collection.immutable.Seq

trait Container {
  val volumes: Seq[Volume]

  def docker(): Option[Container.Docker] = {
    this match {
      case docker: Container.Docker => Some(docker)
      case _ => None
    }
  }

  def getPortMappings: Option[Seq[Container.Docker.PortMapping]] = {
    for {
      d <- docker
      n <- d.network if n == ContainerInfo.DockerInfo.Network.BRIDGE || n == ContainerInfo.DockerInfo.Network.USER
      pms <- d.portMappings
    } yield pms
  }

  def hostPorts: Option[Seq[Option[Int]]] =
    for (pms <- getPortMappings) yield pms.map(_.hostPort)

  def servicePorts: Option[Seq[Int]] =
    for (pms <- getPortMappings) yield pms.map(_.servicePort)
}

object Container {

  case class Mesos(volumes: Seq[Volume] = Seq.empty) extends Container

  case class Docker(
    volumes: Seq[Volume] = Seq.empty,
    image: String = "",
    network: Option[ContainerInfo.DockerInfo.Network] = None,
    portMappings: Option[Seq[Docker.PortMapping]] = None,
    privileged: Boolean = false,
    parameters: Seq[Parameter] = Nil,
    forcePullImage: Boolean = false) extends Container

  object Docker {

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

      def networkHostPortValidator(docker: Docker): Validator[PortMapping] =
        isTrue[PortMapping]("hostPort is required for BRIDGE mode.") { pm =>
          docker.network match {
            case Some(ContainerInfo.DockerInfo.Network.BRIDGE) => pm.hostPort.isDefined
            case _ => true
          }
        }

      val portMappingsValidator = validator[Seq[PortMapping]] { portMappings =>
        portMappings is every(valid)
        portMappings is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
      }

      def validForDocker(docker: Docker): Validator[Seq[PortMapping]] = validator[Seq[PortMapping]] { pm =>
        pm is every(valid(PortMapping.networkHostPortValidator(docker)))
      }
    }

    val validDockerContainer: Validator[Container.Docker] = validator[Container.Docker] { docker =>
      docker.portMappings is optional(PortMapping.portMappingsValidator and PortMapping.validForDocker(docker))
    }
  }

  implicit val validContainer: Validator[Container] = {
    val validGeneralContainer = validator[Container] { container =>
      container.volumes is every(valid)
    }

    new Validator[Container] {
      override def apply(container: Container): Result = container match {
        case _: Mesos => Success
        case docker: Docker => validate(docker)(Docker.validDockerContainer)
      }
    } and validGeneralContainer
  }

  /**
    * An intermediate structure that parallels the external JSON API for containers.
    * This allows for validation of all JSON input combinations,
    * before they have been reduced to a single outcome of type Container
    * which would cover up too many possible error conditions.
    * Furthrmore, we can provide trivial Formats implementation via apply/unapply.
    */
  case class Mask(
      `type`: ContainerInfo.Type,
      volumes: Seq[Volume] = Nil,
      docker: Option[Mask.Docker]) {

    def toContainer(): Container = {
      // When writing tests against this validation,
      // note that paths begin at the container level (not the app level)
      // and thus do not contain the prefix "/container".
      validateOrThrow(this)

      docker match {
        case Some(d) =>
          Container.Docker(
            volumes,
            d.image,
            d.network,
            d.portMappings,
            d.privileged,
            d.parameters,
            d.forcePullImage
          )
        case _ =>
          Container.Mesos(volumes)
      }
    }
  }

  object Mask {

    def fromContainer(container: Container): Mask = {
      container match {
        case m: Container.Mesos =>
          Mask(ContainerInfo.Type.MESOS, m.volumes, None)
        case d: Container.Docker =>
          Mask(ContainerInfo.Type.DOCKER, d.volumes, Some(Mask.Docker(
            d.image,
            d.network,
            d.portMappings,
            d.privileged,
            d.parameters,
            d.forcePullImage
          )))
      }
    }

    case class Docker(
      image: String = "",
      network: Option[ContainerInfo.DockerInfo.Network] = None,
      portMappings: Option[Seq[Container.Docker.PortMapping]] = None,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Nil,
      forcePullImage: Boolean = false)

    object Docker {
      import Container.Docker.PortMapping

      val HostPortDefault = AppDefinition.RandomPortValue // HostPortDefault only applies when in BRIDGE mode

      def withDefaultPortMappings(
        image: String = "",
        network: Option[ContainerInfo.DockerInfo.Network] = None,
        portMappings: Option[Seq[Container.Docker.PortMapping]] = None,
        privileged: Boolean = false,
        parameters: Seq[Parameter] = Nil,
        forcePullImage: Boolean = false): Docker = Docker(
        image = image,
        network = network,
        portMappings = network match {
          case Some(networkMode) if networkMode == ContainerInfo.DockerInfo.Network.BRIDGE =>
            portMappings.map(_.map { m =>
              m match {
                // backwards compat: when in BRIDGE mode, missing host ports default to zero
                case PortMapping(x, None, y, z, w, a) => PortMapping(x, Some(HostPortDefault), y, z, w, a)
                case _ => m
              }
            })
          case _ => portMappings
        },
        privileged = privileged,
        parameters = parameters,
        forcePullImage = forcePullImage)
    }

    implicit val validContainerMask: Validator[Container.Mask] = {
      val validDockerContainerMask: Validator[Container.Mask] = validator[Container.Mask] { mask =>
        mask.docker is notEmpty
      }

      val validMesosContainerMask: Validator[Container.Mask] = validator[Container.Mask] { mask =>
        mask.docker is empty
      }

      new Validator[Container.Mask] {
        override def apply(mask: Container.Mask): Result = mask.`type` match {
          case ContainerInfo.Type.MESOS => validate(mask)(validMesosContainerMask)
          case ContainerInfo.Type.DOCKER => validate(mask)(validDockerContainerMask)
          case _ => Failure(Set(RuleViolation(mask.`type`, "unknown", None)))
        }
      }
    }
  }
}

