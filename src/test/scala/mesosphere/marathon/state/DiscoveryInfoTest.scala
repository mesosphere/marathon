package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.state.DiscoveryInfo.Port
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.{ Protos => MesosProtos }
import play.api.libs.json.{ JsError, JsPath, Json }

class DiscoveryInfoTest extends UnitTest {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {
    lazy val emptyDiscoveryInfo = DiscoveryInfo()

    lazy val discoveryInfoWithPort = DiscoveryInfo(
      ports = Seq(Port(name = "http", number = 80, protocol = "tcp", labels = Map("VIP_0" -> "192.168.0.1:80")))
    )
    lazy val discoveryInfoWithTwoPorts = DiscoveryInfo(
      ports = Seq(
        Port(name = "dns", number = 53, protocol = "udp"),
        Port(name = "http", number = 80, protocol = "tcp")
      )
    )
    lazy val discoveryInfoWithTwoPorts2 = DiscoveryInfo(
      ports = Seq(
        Port(name = "dnsudp", number = 53, protocol = "udp"),
        Port(name = "dnstcp", number = 53, protocol = "tcp")
      )
    )
  }

  def fixture(): Fixture = new Fixture

  "DiscoveryInfo" should {
    "ToProto default DiscoveryInfo" in {
      val f = fixture()
      val proto = f.emptyDiscoveryInfo.toProto

      proto should be(Protos.DiscoveryInfo.getDefaultInstance)
    }

    "ToProto with one port" in {
      val f = fixture()
      val proto = f.discoveryInfoWithPort.toProto

      val portProto =
        MesosProtos.Port.newBuilder()
          .setName("http")
          .setNumber(80)
          .setProtocol("tcp")
          .setLabels(
            MesosProtos.Labels.newBuilder.addLabels(
              MesosProtos.Label.newBuilder
                .setKey("VIP_0")
                .setValue("192.168.0.1:80")))
          .build()

      proto.getPortsList.head should equal(portProto)
    }

    "ConstructFromProto with default proto" in {
      val f = fixture()

      val defaultProto = Protos.DiscoveryInfo.newBuilder.build
      val result = DiscoveryInfo.fromProto(defaultProto)
      result should equal(f.emptyDiscoveryInfo)
    }

    "ConstructFromProto with port" in {
      val f = fixture()

      val portProto =
        MesosProtos.Port.newBuilder()
          .setName("http")
          .setNumber(80)
          .setProtocol("tcp")
          .setLabels(
            MesosProtos.Labels.newBuilder.addLabels(
              MesosProtos.Label.newBuilder
                .setKey("VIP_0")
                .setValue("192.168.0.1:80")))
          .build()

      val protoWithPort = Protos.DiscoveryInfo.newBuilder
        .addAllPorts(Seq(portProto))
        .build

      val result = DiscoveryInfo.fromProto(protoWithPort)
      result should equal(f.discoveryInfoWithPort)
    }

    "JSON Serialization round-trip emptyDiscoveryInfo" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.emptyDiscoveryInfo)
    }

    "JSON Serialization round-trip discoveryInfoWithPort" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.discoveryInfoWithPort)
    }

    def fromJson(json: String): DiscoveryInfo = {
      Json.fromJson[DiscoveryInfo](Json.parse(json)).get
    }

    "Read empty discovery info" in {
      val json =
        """
      {
        "ports": []
      }
      """

      val readResult = fromJson(json)

      val f = fixture()
      assert(readResult == f.emptyDiscoveryInfo)
    }

    "Read discovery info with one port" in {
      val json =
        """
      {
        "ports": [
          { "name": "http", "number": 80, "protocol": "tcp", "labels": { "VIP_0": "192.168.0.1:80" } }
        ]
      }
      """

      val readResult = fromJson(json)

      val f = fixture()
      assert(readResult == f.discoveryInfoWithPort)
    }

    "Read discovery info with two ports" in {
      val json =
        """
      {
        "ports": [
          { "name": "dns", "number": 53, "protocol": "udp" },
          { "name": "http", "number": 80, "protocol": "tcp" }
        ]
      }
      """

      val readResult = fromJson(json)

      val f = fixture()
      assert(readResult == f.discoveryInfoWithTwoPorts)
    }

    "Read discovery info with two ports with the same port number" in {
      val json =
        """
      {
        "ports": [
          { "name": "dnsudp", "number": 53, "protocol": "udp" },
          { "name": "dnstcp", "number": 53, "protocol": "tcp" }
        ]
      }
      """

      val readResult = fromJson(json)

      val f = fixture()
      assert(readResult == f.discoveryInfoWithTwoPorts2)
    }

    "Read discovery info with two ports with duplicate port/number" in {
      val json =
        """
      {
        "ports": [
          { "name": "dns1", "number": 53, "protocol": "udp" },
          { "name": "dns2", "number": 53, "protocol": "udp" }
        ]
      }
      """

      val readResult = Json.fromJson[DiscoveryInfo](Json.parse(json))
      readResult should be(JsError(
        JsPath() \ "ports",
        "There may be only one port with a particular port number/protocol combination.")
      )
    }

    "Read discovery info with two ports with duplicate name" in {
      val json =
        """
      {
        "ports": [
          { "name": "dns1", "number": 53, "protocol": "udp" },
          { "name": "dns1", "number": 53, "protocol": "tcp" }
        ]
      }
      """

      val readResult = Json.fromJson[DiscoveryInfo](Json.parse(json))
      readResult should be(JsError(
        JsPath() \ "ports",
        "Port names are not unique.")
      )
    }

    "Read discovery info with a port with an invalid protocol" in {
      val json =
        """
      {
        "ports": [
          { "name": "http", "number": 80, "protocol": "foo" }
        ]
      }
      """

      val readResult = Json.fromJson[DiscoveryInfo](Json.parse(json))
      readResult should be(JsError(
        (JsPath() \ "ports")(0) \ "protocol",
        "Invalid protocol. Only 'udp' or 'tcp' are allowed.")
      )
    }

    "Read discovery info with a port with an invalid name" in {
      val json =
        """
      {
        "ports": [
          { "name": "???", "number": 80, "protocol": "tcp" }
        ]
      }
      """

      val readResult = Json.fromJson[DiscoveryInfo](Json.parse(json))
      readResult should be(JsError(
        (JsPath() \ "ports")(0) \ "name",
        s"Port name must fully match regular expression ${PortAssignment.PortNamePattern}")
      )
    }
  }
}
