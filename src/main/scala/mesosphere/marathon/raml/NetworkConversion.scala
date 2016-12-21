package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod
import mesosphere.marathon.state

trait NetworkConversion {

  implicit val networkRamlReader: Reads[Network, pod.Network] =
    Reads { raml =>
      raml.mode match {
        case NetworkMode.Host => pod.HostNetwork
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
    case pod.HostNetwork => Network(mode = NetworkMode.Host)
  }

  implicit val protocolWrites: Writes[String, NetworkProtocol] = Writes {
    case "tcp" => NetworkProtocol.Tcp
    case "udp" => NetworkProtocol.Udp
    case "udp,tcp" => NetworkProtocol.UdpTcp
  }

  implicit val portDefinitionWrites: Writes[state.PortDefinition, PortDefinition] = Writes { port =>
    PortDefinition(port.port, port.labels, port.name, port.protocol.toRaml[NetworkProtocol])
  }

  implicit val portMappingWrites: Writes[state.Container.PortMapping, DockerPortMapping] = Writes { portMapping =>
    DockerPortMapping(
      containerPort = portMapping.containerPort,
      hostPort = portMapping.hostPort,
      labels = portMapping.labels,
      name = portMapping.name,
      protocol = portMapping.protocol.toRaml[NetworkProtocol],
      servicePort = portMapping.servicePort
    )
  }

  implicit val discoveryInfoPortWrites: Writes[state.DiscoveryInfo.Port, IpDiscoveryPort] = Writes { port =>
    IpDiscoveryPort(port.number, port.name, port.protocol.toRaml[NetworkProtocol])
  }
  implicit val discoveryInfoWrites: Writes[state.DiscoveryInfo, IpDiscovery] = Writes { discovery =>
    IpDiscovery(discovery.ports.toRaml)
  }

  implicit val ipAddressWrites: Writes[state.IpAddress, IpAddress] = Writes { ip =>
    val discovery =
      if (ip.discoveryInfo.isEmpty) None
      else Some(ip.discoveryInfo.toRaml)

    IpAddress(discovery, ip.groups, ip.labels, ip.networkName)
  }
}

object NetworkConversion extends NetworkConversion
