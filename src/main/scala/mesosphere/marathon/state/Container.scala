package mesosphere.marathon.state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._

import org.apache.mesos.Protos.ContainerInfo

import scala.collection.immutable.Seq

trait Container {
  val volumes: Seq[Volume]

  def docker(): Option[Container.DockerDocker] = {
    this match {
      case docker: Container.DockerDocker => Some(docker)
      case _ => None
    }
  }

  def getPortMappings: Option[Seq[Container.DockerDocker.PortMapping]] = {
    for {
      d <- docker if !d.portMappings.isEmpty
      n <- d.network if n == ContainerInfo.DockerInfo.Network.BRIDGE || n == ContainerInfo.DockerInfo.Network.USER
    } yield d.portMappings
  }

  def hostPorts: Option[Seq[Option[Int]]] =
    for (pms <- getPortMappings) yield pms.map(_.hostPort)

  def servicePorts: Option[Seq[Int]] =
    for (pms <- getPortMappings) yield pms.map(_.servicePort)
}

object Container {

  case class Mesos(volumes: Seq[Volume] = Seq.empty) extends Container

  case class DockerDocker(
    volumes: Seq[Volume] = Seq.empty,
    image: String = "",
    network: Option[ContainerInfo.DockerInfo.Network] = None,
    portMappings: Seq[DockerDocker.PortMapping] = Seq.empty,
    privileged: Boolean = false,
    parameters: Seq[Parameter] = Nil,
    forcePullImage: Boolean = false) extends Container

  object DockerDocker {

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

      def networkHostPortValidator(docker: DockerDocker): Validator[PortMapping] =
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

      def validForDocker(docker: DockerDocker): Validator[Seq[PortMapping]] = validator[Seq[PortMapping]] { pm =>
        pm is every(valid(PortMapping.networkHostPortValidator(docker)))
      }
    }

    val validDockerDockerContainer = validator[DockerDocker] { docker =>
      docker.image is notEmpty
      docker.portMappings is PortMapping.portMappingsValidator and PortMapping.validForDocker(docker)
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
        case dd: DockerDocker => validate(dd)(DockerDocker.validDockerDockerContainer)
        case md: MesosDocker => validate(md)(MesosDocker.validMesosDockerContainer)
        case ma: MesosAppC => validate(ma)(MesosAppC.validMesosAppCContainer)
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
      docker: Option[Mask.Docker] = None,
      appc: Option[Mask.AppC] = None) {

    def toContainer(): Container = {
      // When writing tests against this validation,
      // note that paths begin at the container level (not the app level)
      // and thus do not contain the prefix "/container".
      validateOrThrow(this)

      docker match {
        case Some(d) =>
          if (`type` == ContainerInfo.Type.DOCKER) {
            Container.DockerDocker(
              volumes,
              d.image,
              d.network,
              d.portMappings.getOrElse(Seq.empty),
              d.privileged.getOrElse(false),
              d.parameters.getOrElse(Seq.empty),
              d.forcePullImage
            )
          } else {
            Container.MesosDocker(
              volumes,
              d.image,
              d.credential,
              d.forcePullImage
            )
          }
        case _ =>
          appc match {
            case Some(a) =>
              Container.MesosAppC(
                volumes,
                a.image,
                a.id,
                a.labels,
                a.forcePullImage
              )
            case _ =>
              Container.Mesos(volumes)
          }
      }
    }
  }

  object Mask {

    def fromContainer(container: Container): Mask = {
      container match {
        case m: Container.Mesos =>
          Mask(ContainerInfo.Type.MESOS, m.volumes, None)
        case dd: Container.DockerDocker =>
          Mask(ContainerInfo.Type.DOCKER, dd.volumes, docker = Some(Mask.Docker(
            image = dd.image,
            network = dd.network,
            portMappings = if (dd.portMappings.isEmpty) None else Some(dd.portMappings),
            privileged = Some(dd.privileged),
            parameters = if (dd.parameters.isEmpty) None else Some(dd.parameters),
            forcePullImage = dd.forcePullImage
          )))
        case md: MesosDocker =>
          Mask(ContainerInfo.Type.MESOS, md.volumes, docker = Some(Mask.Docker(
            image = md.image,
            credential = md.credential,
            forcePullImage = md.forcePullImage
          )))
        case ma: MesosAppC =>
          Mask(ContainerInfo.Type.MESOS, ma.volumes, appc = Some(Mask.AppC(
            ma.image,
            ma.id,
            ma.labels,
            ma.forcePullImage
          )))
      }
    }

    case class Docker(
      image: String = "",
      network: Option[ContainerInfo.DockerInfo.Network] = None,
      portMappings: Option[Seq[Container.DockerDocker.PortMapping]] = None,
      privileged: Option[Boolean] = None,
      parameters: Option[Seq[Parameter]] = None,
      credential: Option[Credential] = None,
      forcePullImage: Boolean = false)

    object Docker {
      import Container.DockerDocker.PortMapping

      val HostPortDefault = AppDefinition.RandomPortValue // HostPortDefault only applies when in BRIDGE mode

      def withDefaultPortMappings(
        image: String = "",
        network: Option[ContainerInfo.DockerInfo.Network] = None,
        portMappings: Option[Seq[Container.DockerDocker.PortMapping]] = None,
        privileged: Option[Boolean] = None,
        parameters: Option[Seq[Parameter]] = None,
        credential: Option[Container.Credential] = None,
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
        credential = credential,
        forcePullImage = forcePullImage)
    }

    case class AppC(
      image: String = "",
      id: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String],
      forcePullImage: Boolean = false)

    // Validation of structural constraints on the allowed JSON syntax,
    // mainly what fields may be specified for what type of container.
    implicit val validContainerMask: Validator[Container.Mask] = {
      val validDockerDockerMask: Validator[Container.Mask.Docker] = validator[Container.Mask.Docker] { mask =>
        mask.credential is empty
      }

      val validDockerDockerContainerMask: Validator[Container.Mask] = validator[Container.Mask] { mask =>
        mask.appc is empty
        mask.docker is notEmpty
        mask.docker is optional(validDockerDockerMask)
      }

      val validMesosDockerMask: Validator[Container.Mask.Docker] = validator[Container.Mask.Docker] { mask =>
        mask.network is empty
        mask.portMappings is empty
        mask.privileged is empty
        mask.parameters is empty
      }

      val validMesosDockerContainerMask: Validator[Container.Mask] = validator[Container.Mask] { mask =>
        mask.appc is empty
        mask.docker is notEmpty
        mask.docker is optional(validMesosDockerMask)
      }

      val validMesosAppCContainerMask: Validator[Container.Mask] = validator[Container.Mask] { mask =>
        mask.docker is empty
      }

      new Validator[Container.Mask] {
        override def apply(mask: Container.Mask): Result = mask.`type` match {
          case ContainerInfo.Type.MESOS =>
            if (mask.docker.isDefined) {
              validate(mask)(validMesosDockerContainerMask)
            } else if (mask.appc.isDefined) {
              validate(mask)(validMesosAppCContainerMask)
            } else {
              Success
            }
          case ContainerInfo.Type.DOCKER =>
            validate(mask)(validDockerDockerContainerMask)
          case _ =>
            Failure(Set(RuleViolation(mask.`type`, "unknown", None)))
        }
      }
    }
  }
}

