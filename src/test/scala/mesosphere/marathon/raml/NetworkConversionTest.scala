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
    "convert between model and raml" in {
      val portMapping = PortMapping(23, Some(123), 0, "udp", Some("name"), Map("foo" -> "bla"), List("network-name"))
      val roundTripped = portMapping.toRaml[ContainerPortMapping].fromRaml[PortMapping]
      roundTripped shouldBe portMapping
    }

    "convert between protobuf and raml" in {
      val b = Protos.ExtendedContainerInfo.PortMapping.newBuilder
        .setName("port-jr")
        .addNetworkNames("network-1")
        .addNetworkNames("network-2")
        .setHostPort(123)
        .setContainerPort(456)
        .setProtocol("udp")
        .setServicePort(999)

      b.addLabelsBuilder.setKey("business-level").setValue("serious")

      val raml = b.build.toRaml

      raml shouldBe ContainerPortMapping(
        name = Some("port-jr"),
        hostPort = Some(123),
        containerPort = 456,
        protocol = NetworkProtocol.Udp,
        servicePort = 999,
        labels = Map("business-level" -> "serious"),
        networkNames = List("network-1", "network-2")
      )
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
