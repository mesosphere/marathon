package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.{ Network => DockerNetworkMode }

trait NetworkConversion {

  implicit val networkRamlReader: Reads[Network, pod.Network] =
    Reads { raml =>
      raml.mode match {
        case NetworkMode.Host => pod.HostNetwork
        case NetworkMode.ContainerBridge => pod.BridgeNetwork(raml.labels)
        case NetworkMode.Container => pod.ContainerNetwork(
          // TODO(PODS): shouldn't this be caught by validation?
          raml.name.getOrElse(throw new IllegalArgumentException("container network must specify a name")),
          raml.labels
        )
      }
    }

  implicit val networkRamlWriter: Writes[pod.Network, Network] = Writes {
    case cnet: pod.ContainerNetwork =>
      Network(
        name = Some(cnet.name),
        mode = NetworkMode.Container,
        labels = cnet.labels
      )
    case br: pod.BridgeNetwork =>
      Network(
        mode = NetworkMode.ContainerBridge,
        labels = br.labels
      )
    case pod.HostNetwork => Network(mode = NetworkMode.Host)
  }

  implicit val protocolWrites: Writes[String, NetworkProtocol] = Writes { protocol =>
    NetworkProtocol.fromString(protocol).getOrElse(throw new IllegalStateException(s"unsupported protocol $protocol"))
  }

  implicit val portDefinitionWrites: Writes[state.PortDefinition, PortDefinition] = Writes { port =>
    PortDefinition(port.port, port.labels, port.name, port.protocol.toRaml[NetworkProtocol])
  }

  implicit val portDefinitionRamlReader: Reads[PortDefinition, state.PortDefinition] = Reads { portDef =>
    state.PortDefinition(
      port = portDef.port,
      protocol = portDef.protocol.value,
      name = portDef.name,
      labels = portDef.labels
    )
  }

  implicit val portMappingWrites: Writes[state.Container.PortMapping, ContainerPortMapping] = Writes { portMapping =>
    ContainerPortMapping(
      containerPort = portMapping.containerPort,
      hostPort = portMapping.hostPort,
      labels = portMapping.labels,
      name = portMapping.name,
      protocol = portMapping.protocol.toRaml[NetworkProtocol],
      servicePort = portMapping.servicePort
    )
  }

  implicit val portMappingRamlReader: Reads[ContainerPortMapping, state.Container.PortMapping] = Reads {
    case ContainerPortMapping(containerPort, hostPort, labels, name, protocol, servicePort) =>
      import state.Container.PortMapping._
      state.Container.PortMapping(
        containerPort = containerPort,
        hostPort = hostPort.orElse(defaultInstance.hostPort),
        servicePort = servicePort,
        protocol = protocol.value,
        name = name,
        labels = labels
      )
  }

  implicit val containerPortMappingProtoRamlWriter: Writes[Protos.ExtendedContainerInfo.PortMapping, ContainerPortMapping] = Writes { mapping =>
    ContainerPortMapping(
      containerPort = mapping.whenOrElse(_.hasContainerPort, _.getContainerPort, ContainerPortMapping.DefaultContainerPort),
      hostPort = mapping.when(_.hasHostPort, _.getHostPort).orElse(ContainerPortMapping.DefaultHostPort),
      labels = mapping.whenOrElse(_.getLabelsCount > 0, _.getLabelsList.flatMap(_.fromProto)(collection.breakOut), ContainerPortMapping.DefaultLabels),
      name = mapping.when(_.hasName, _.getName).orElse(ContainerPortMapping.DefaultName),
      protocol = mapping.when(_.hasProtocol, _.getProtocol).flatMap(NetworkProtocol.fromString).getOrElse(ContainerPortMapping.DefaultProtocol),
      servicePort = mapping.whenOrElse(_.hasServicePort, _.getServicePort, ContainerPortMapping.DefaultServicePort)
    )
  }

  implicit val dockerPortMappingProtoRamlWriter: Writes[Protos.ExtendedContainerInfo.DockerInfo.ObsoleteDockerPortMapping, ContainerPortMapping] = Writes { mapping =>
    ContainerPortMapping(
      containerPort = mapping.whenOrElse(_.hasContainerPort, _.getContainerPort, ContainerPortMapping.DefaultContainerPort),
      hostPort = mapping.when(_.hasHostPort, _.getHostPort).orElse(ContainerPortMapping.DefaultHostPort),
      labels = mapping.whenOrElse(_.getLabelsCount > 0, _.getLabelsList.flatMap(_.fromProto)(collection.breakOut), ContainerPortMapping.DefaultLabels),
      name = mapping.when(_.hasName, _.getName).orElse(ContainerPortMapping.DefaultName),
      protocol = mapping.whenOrElse(_.hasProtocol, _.getProtocol.toRaml[NetworkProtocol], ContainerPortMapping.DefaultProtocol),
      servicePort = mapping.whenOrElse(_.hasServicePort, _.getServicePort, ContainerPortMapping.DefaultServicePort)
    )
  }

  implicit val dockerNetworkInfoWrites: Writes[DockerNetworkMode, DockerNetwork] = Writes {
    case DockerNetworkMode.BRIDGE => DockerNetwork.Bridge
    case DockerNetworkMode.HOST => DockerNetwork.Host
    case DockerNetworkMode.USER => DockerNetwork.User
    case DockerNetworkMode.NONE => DockerNetwork.None
  }

  implicit val networkProtoRamlWriter: Writes[Protos.NetworkDefinition, Network] = Writes { net =>
    import Protos.NetworkDefinition.Mode._
    val mode = net.getMode match {
      case HOST => NetworkMode.Host
      case BRIDGE => NetworkMode.ContainerBridge
      case CONTAINER => NetworkMode.Container
      case badMode => throw new IllegalStateException(s"unsupported network mode $badMode")
    }
    Network(
      name = if (net.hasName) Option(net.getName) else Network.DefaultName,
      mode = mode,
      labels = if (net.getLabelsCount > 0) net.getLabelsList.to[Seq].fromProto else Network.DefaultLabels
    )
  }
}

object NetworkConversion extends NetworkConversion
