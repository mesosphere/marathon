package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.state.Container.PortMapping

class NetworkConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(title: => String, net: => core.pod.Network, raml: => Network): Unit = {
    s"$title converts to protobuf, then to RAML" in {
      val proto = core.pod.Network.toProto(net)
      val proto2Raml = proto.toRaml
      proto2Raml should be(raml)
    }
    s"$title converts to RAML" in {
      val core2Raml = net.toRaml
      core2Raml should be(raml)
    }
  }

  "NetworkConversion protocol conversion" should {
    "convert correctly" in {
      "tcp".toRaml[NetworkProtocol] should be(NetworkProtocol.Tcp)
      "udp".toRaml[NetworkProtocol] should be(NetworkProtocol.Udp)
      "udp,tcp".toRaml[NetworkProtocol] should be(NetworkProtocol.UdpTcp)
    }
  }
  "NetworkConversion port definition conversion" should {
    "convert correctly" in {
      val portDefinition = state.PortDefinition(23, "udp", Some("test"), Map("foo" -> "bla"))
      val raml = portDefinition.toRaml[PortDefinition]
      raml.port should be(portDefinition.port)
      raml.name should be(portDefinition.name)
      raml.labels should be(portDefinition.labels)
      raml.protocol should be(NetworkProtocol.Udp)
    }
  }
  "NetworkConversion port mapping conversion" should {
    "convert correctly" in {
      val portMapping = PortMapping(23, Some(123), 0, "udp", Some("name"), Map("foo" -> "bla"))
      val raml = portMapping.toRaml[ContainerPortMapping]
      raml.containerPort should be(portMapping.containerPort)
      raml.hostPort should be(portMapping.hostPort)
      raml.servicePort should be(portMapping.servicePort)
      raml.name should be(portMapping.name)
      raml.labels should be(portMapping.labels)
      raml.protocol should be(NetworkProtocol.Udp)
    }
  }
  "NetworkConversion network conversion" should {
    behave like convertToProtobufThenToRAML(
      "host network",
      core.pod.HostNetwork, Network(mode = NetworkMode.Host))
    behave like convertToProtobufThenToRAML(
      "container network named 'foo'",
      core.pod.ContainerNetwork("foo", Map("qwe" -> "asd")),
      Network(name = Option("foo"), mode = NetworkMode.Container, labels = Map("qwe" -> "asd")))
    behave like convertToProtobufThenToRAML(
      "bridge network",
      core.pod.BridgeNetwork(Map("qwe" -> "asd")),
      Network(mode = NetworkMode.ContainerBridge, labels = Map("qwe" -> "asd")))
  }
}
