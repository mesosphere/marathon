package mesosphere.marathon.state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._

import org.apache.mesos.Protos.ContainerInfo

import scala.collection.immutable.Seq

sealed trait Container {
  val volumes: Seq[Volume]

  // TODO(nfnt): Remove this field and use type matching instead.
  def docker(): Option[Container.Docker] = {
    this match {
      case docker: Container.Docker => Some(docker)
      case _ => None
    }
  }

  // TODO(jdef): Someone should really fix this to not be Option[Seq[]] - we can't express that in protos anyways!
  def portMappings: Option[Seq[Container.Docker.PortMapping]] = None

  def hostPorts: Option[Seq[Option[Int]]] =
    for (pms <- portMappings) yield pms.map(_.hostPort)

  def servicePorts: Option[Seq[Int]] =
    for (pms <- portMappings) yield pms.map(_.servicePort)
}

object Container {

  case class Mesos(volumes: Seq[Volume] = Seq.empty) extends Container

  case class Docker(
    volumes: Seq[Volume] = Seq.empty,
    image: String = "",
    network: Option[ContainerInfo.DockerInfo.Network] = None,
    override val portMappings: Option[Seq[Docker.PortMapping]] = None,
    privileged: Boolean = false,
    parameters: Seq[Parameter] = Nil,
    forcePullImage: Boolean = false) extends Container

  object Docker {

    def withDefaultPortMappings(
      volumes: Seq[Volume],
      image: String = "",
      network: Option[ContainerInfo.DockerInfo.Network] = None,
      portMappings: Option[Seq[Docker.PortMapping]] = None,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Seq.empty,
      forcePullImage: Boolean = false): Docker = Docker(
      volumes = volumes,
      image = image,
      network = network,
      portMappings = network match {
        case Some(networkMode) if networkMode == ContainerInfo.DockerInfo.Network.BRIDGE =>
          portMappings.map(_.map { m =>
            m match {
              // backwards compat: when in BRIDGE mode, missing host ports default to zero
              case PortMapping(x, None, y, z, w, a) => PortMapping(x, Some(PortMapping.HostPortDefault), y, z, w, a)
              case _ => m
            }
          })
        case Some(networkMode) if networkMode == ContainerInfo.DockerInfo.Network.USER => portMappings
        case _ => None
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

    val validDockerContainer = validator[Docker] { docker =>
      docker.image is notEmpty
      docker.portMappings is optional(PortMapping.portMappingsValidator and PortMapping.validForDocker(docker))
    }
  }

  case class Credential(
    principal: String,
    secret: Option[String] = None)

  case class MesosDocker(
    volumes: Seq[Volume] = Seq.empty,
    image: String = "",
    credential: Option[Credential] = None,
    forcePullImage: Boolean = false) extends Container

  object MesosDocker {
    val validMesosDockerContainer = validator[MesosDocker] { docker =>
      docker.image is notEmpty
    }
  }

  case class MesosAppC(
    volumes: Seq[Volume] = Seq.empty,
    image: String = "",
    id: Option[String] = None,
    labels: Map[String, String] = Map.empty[String, String],
    forcePullImage: Boolean = false) extends Container

  object MesosAppC {
    val prefix = "sha512-"

    val validId: Validator[String] =
      isTrue[String](s"id must begin with '$prefix',") { id =>
        id.startsWith(prefix)
      } and isTrue[String](s"id must contain non-empty digest after '$prefix'.") { id =>
        id.length > prefix.length
      }

    val validMesosAppCContainer = validator[MesosAppC] { appc =>
      appc.image is notEmpty
      appc.id is optional(validId)
    }
  }

  implicit val validContainer: Validator[Container] = {
    val validGeneralContainer = validator[Container] { container =>
      container.volumes is every(valid)
    }

    new Validator[Container] {
      override def apply(container: Container): Result = container match {
        case _: Mesos => Success
        case dd: Docker => validate(dd)(Docker.validDockerContainer)
        case md: MesosDocker => validate(md)(MesosDocker.validMesosDockerContainer)
        case ma: MesosAppC => validate(ma)(MesosAppC.validMesosAppCContainer)
      }
    } and validGeneralContainer
  }
}

