package mesosphere.marathon.state

import org.apache.mesos.{ Protos => MesosProtos }
import mesosphere.marathon.{ Protos, MarathonSpec }
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.state.DiscoveryInfo.Port
import org.scalatest.Matchers
import play.api.libs.json.{ JsPath, JsError, Json }
import scala.collection.JavaConverters._

class DiscoveryInfoTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {
    lazy val emptyDiscoveryInfo = DiscoveryInfo()

    lazy val discoveryInfoWithPort = DiscoveryInfo(
      ports = Seq(Port(name = "http", number = 80, protocol = "tcp"))
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

  test("ToProto default DiscoveryInfo") {
    val f = fixture()
    val proto = f.emptyDiscoveryInfo.toProto

    proto should be(Protos.DiscoveryInfo.getDefaultInstance)
  }

  test("ToProto with one port") {
    val f = fixture()
    val proto = f.discoveryInfoWithPort.toProto

    val portProto =
      MesosProtos.Port.newBuilder()
        .setName("http")
        .setNumber(80)
        .setProtocol("tcp")
        .build()

    proto.getPortsList.asScala.head should equal(portProto)
  }

  test("ConstructFromProto with default proto") {
    val f = fixture()

    val defaultProto = Protos.DiscoveryInfo.newBuilder.build
    val result = DiscoveryInfo.fromProto(defaultProto)
    result should equal(f.emptyDiscoveryInfo)
  }

  test("ConstructFromProto with port") {
    val f = fixture()

    val portProto =
      MesosProtos.Port.newBuilder()
        .setName("http")
        .setNumber(80)
        .setProtocol("tcp")
        .build()

    val protoWithPort = Protos.DiscoveryInfo.newBuilder
      .addAllPorts(Seq(portProto).asJava)
      .build

    val result = DiscoveryInfo.fromProto(protoWithPort)
    result should equal(f.discoveryInfoWithPort)
  }

  test("JSON Serialization round-trip emptyDiscoveryInfo") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.emptyDiscoveryInfo)
  }

  test("JSON Serialization round-trip discoveryInfoWithPort") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.discoveryInfoWithPort)
  }

  private[this] def fromJson(json: String): DiscoveryInfo = {
    Json.fromJson[DiscoveryInfo](Json.parse(json)).get
  }

  test("Read empty discovery info") {
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

  test("Read discovery info with one port") {
    val json =
      """
      {
        "ports": [
          { "name": "http", "number": 80, "protocol": "tcp" }
        ]
      }
      """

    val readResult = fromJson(json)

    val f = fixture()
    assert(readResult == f.discoveryInfoWithPort)
  }

  test("Read discovery info with two ports") {
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

  test("Read discovery info with two ports with the same port number") {
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

  test("Read discovery info with two ports with duplicate port/number") {
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

  test("Read discovery info with two ports with duplicate name") {
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

  test("Read discovery info with a port with an invalid protocol") {
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
}
