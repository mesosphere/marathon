package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.pod.Network

import scala.collection.immutable.Seq

object Container {

  case class Mesos(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      override val portMappings: Seq[PortMapping] = Nil
  ) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  case class Docker(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Nil,
      forcePullImage: Boolean = false) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  object Docker {
    implicit val validDockerContainer: Validator[Docker] = validator[Docker] { docker =>
      docker.image is notEmpty
    }
  }

  object PortMapping {
    val TCP = raml.NetworkProtocol.Tcp.value
    val UDP = raml.NetworkProtocol.Udp.value
    val UDP_TCP = raml.NetworkProtocol.UdpTcp.value
    val defaultInstance = PortMapping(name = Option("default"))

    val HostPortDefault = AppDefinition.RandomPortValue // HostPortDefault only applies when in BRIDGE mode
  }

  case class Credential(
      principal: String,
      secret: Option[String] = None)

  case class DockerPullConfig(secret: String)

  case class MesosDocker(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      credential: Option[Credential] = None,
      pullConfig: Option[DockerPullConfig] = None,
      forcePullImage: Boolean = false) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  object MesosDocker {
    val validMesosDockerContainer = validator[MesosDocker] { docker =>
      docker.image is notEmpty
    }
  }

  case class MesosAppC(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      id: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String],
      forcePullImage: Boolean = false) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes) =
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
      container.volumes is every(VolumeWithMount.validVolumeWithMount(enabledFeatures))
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

