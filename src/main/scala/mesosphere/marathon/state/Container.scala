package mesosphere.marathon
package state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.pod.Network

import scala.collection.immutable.Seq

sealed trait Container extends Product with Serializable {

  import Container.{ Docker, PortMapping }

  def portMappings: Seq[PortMapping]
  val volumes: Seq[Volume]

  // TODO(nfnt): Remove this field and use type matching instead.
  def docker: Option[Docker] = {
    this match {
      case docker: Docker => Some(docker)
      case _ => None
    }
  }

  def hostPorts: Seq[Option[Int]] =
    portMappings.map(_.hostPort)

  def servicePorts: Seq[Int] =
    portMappings.map(_.servicePort)

  def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[Volume] = volumes): Container
}

object Container {

  case class Mesos(
      volumes: Seq[Volume] = Seq.empty,
      override val portMappings: Seq[PortMapping] = Nil
  ) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[Volume] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  case class Docker(
      volumes: Seq[Volume] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Nil,
      forcePullImage: Boolean = false) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[Volume] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  object Docker {
    val validDockerContainer = validator[Docker] { docker =>
      docker.image is notEmpty
      docker.portMappings is PortMapping.portMappingsValidator
    }
  }

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
    protocol: String = PortMapping.TCP,
    name: Option[String] = None,
    labels: Map[String, String] = Map.empty[String, String])

  object PortMapping {
    val TCP = raml.NetworkProtocol.Tcp.value
    val UDP = raml.NetworkProtocol.Udp.value
    val UDP_TCP = raml.NetworkProtocol.UdpTcp.value
    val defaultInstance = PortMapping(name = Option("default"))

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
    }

    val portMappingsValidator = validator[Seq[PortMapping]] { portMappings =>
      portMappings is every(valid)
      portMappings is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
    }
  }

  case class Credential(
    principal: String,
    secret: Option[String] = None)

  case class MesosDocker(
      volumes: Seq[Volume] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      credential: Option[Credential] = None,
      forcePullImage: Boolean = false) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[Volume] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  object MesosDocker {
    val validMesosDockerContainer = validator[MesosDocker] { docker =>
      docker.image is notEmpty
    }
  }

  case class MesosAppC(
      volumes: Seq[Volume] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      id: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String],
      forcePullImage: Boolean = false) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[Volume] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

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

  def validContainer(networks: Seq[Network], enabledFeatures: Set[String]): Validator[Container] = {
    import Network._
    val validGeneralContainer = validator[Container] { container =>
      container.volumes is every(Volume.validVolume(enabledFeatures))
    }

    new Validator[Container] {
      override def apply(container: Container): Result = container match {
        case _: Mesos => Success
        case dd: Docker => validate(dd)(Docker.validDockerContainer)
        case md: MesosDocker => validate(md)(MesosDocker.validMesosDockerContainer)
        case ma: MesosAppC => validate(ma)(MesosAppC.validMesosAppCContainer)
      }
    } and
      validGeneralContainer and
      implied(networks.hasBridgeNetworking)(validator[Container] { container =>
        container.portMappings is every(isTrue("hostPort is required for BRIDGE mode.")(_.hostPort.nonEmpty))
      })
  }
}

