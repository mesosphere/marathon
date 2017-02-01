package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.state.Container.PortMapping

class NetworkConversionTest extends UnitTest {
  "NetworkConversion" should {
    "protocol is converted correctly" in {
      "tcp".toRaml[NetworkProtocol] should be(NetworkProtocol.Tcp)
      "udp".toRaml[NetworkProtocol] should be(NetworkProtocol.Udp)
      "udp,tcp".toRaml[NetworkProtocol] should be(NetworkProtocol.UdpTcp)
    }

    "port definition is converted correctly" in {
      val portDefinition = state.PortDefinition(23, "udp", Some("test"), Map("foo" -> "bla"))
      val raml = portDefinition.toRaml[PortDefinition]
      raml.port should be(portDefinition.port)
      raml.name should be(portDefinition.name)
      raml.labels should be(portDefinition.labels)
      raml.protocol should be(NetworkProtocol.Udp)
    }

    "port mappings is converted correctly" in {
      val portMapping = PortMapping(23, Some(123), 0, "udp", Some("name"), Map("foo" -> "bla"))
      val raml = portMapping.toRaml[DockerPortMapping]
      raml.containerPort should be(portMapping.containerPort)
      raml.hostPort should be(portMapping.hostPort)
      raml.servicePort should be(portMapping.servicePort)
      raml.name should be(portMapping.name)
      raml.labels should be(portMapping.labels)
      raml.protocol should be(NetworkProtocol.Udp)
    }

    "DiscoveryInfo.Port is converted correctly" in {
      val port = state.DiscoveryInfo.Port(123, "test", "tcp", Map("foo" -> "bla"))
      val raml = port.toRaml[IpDiscoveryPort]
      raml.name should be(port.name)
      raml.number should be(port.number)
      raml.protocol should be(NetworkProtocol.Tcp)
    }

    "DiscoveryInfo is converted correctly" in {
      val port = state.DiscoveryInfo.Port(123, "test", "tcp", Map("foo" -> "bla"))
      val info = state.DiscoveryInfo(Seq(port))
      val raml = info.toRaml[IpDiscovery]
      raml.ports should have size 1
    }

    "IPAddress is converted correctly" in {
      val port = state.DiscoveryInfo.Port(123, "test", "tcp", Map("foo" -> "bla"))
      val info = state.DiscoveryInfo(Seq(port))
      val ip = state.IpAddress(Seq("name"), Map("foo" -> "bla"), info, Some("network"))
      val raml = ip.toRaml[IpAddress]
      raml.discovery should be(Some(info.toRaml[IpDiscovery]))
      raml.groups should be(ip.groups)
      raml.labels should be(ip.labels)
      raml.networkName should be(ip.networkName)
    }
  }
}
