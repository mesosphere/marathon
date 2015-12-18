package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.Matchers
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class IpAddressTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {

    lazy val defaultIpAddress = IpAddress()

    lazy val ipAddressWithGroups = IpAddress(
      groups = Vector("foo", "bar"),
      labels = Map.empty
    )

    lazy val ipAddressWithGroupsAndLabels = IpAddress(
      groups = Vector("a", "b", "c"),
      labels = Map(
        "foo" -> "bar",
        "baz" -> "buzz"
      )
    )

    lazy val ipAddressWithDiscoveryInfo = IpAddress(
      groups = Vector("a", "b", "c"),
      labels = Map(
        "foo" -> "bar",
        "baz" -> "buzz"
      ),
      discoveryInfo = DiscoveryInfo(
        ports = Vector(DiscoveryInfo.Port(name = "http", number = 80, protocol = "tcp"))
      )
    )
  }

  def fixture(): Fixture = new Fixture

  test("ToProto default IpAddress") {
    // this is slightly artificial, since every IpAddress should have at least one group
    val f = fixture()
    val proto = f.defaultIpAddress.toProto

    // The default IpAddress has an empty DiscoveryInfo
    val defaultIpAddressProto =
      Protos.IpAddress.newBuilder().setDiscoveryInfo(Protos.DiscoveryInfo.getDefaultInstance).build()
    proto should be(defaultIpAddressProto)
  }

  test("ToProto with groups") {
    val f = fixture()
    val proto = f.ipAddressWithGroups.toProto
    proto.getGroupsList.asScala should equal(f.ipAddressWithGroups.groups)
    proto.getLabelsList.asScala should be(empty)
  }

  test("ToProto with groups and labels") {
    val f = fixture()
    val proto = f.ipAddressWithGroupsAndLabels.toProto
    proto.getGroupsList.asScala should equal(f.ipAddressWithGroupsAndLabels.groups)
    proto.getLabelsList.asScala.map(kv => kv.getKey -> kv.getValue).toMap should
      equal(f.ipAddressWithGroupsAndLabels.labels)
  }

  test("ToProto with groups and labels and discovery") {
    val f = fixture()
    val proto = f.ipAddressWithDiscoveryInfo.toProto

    val portProto = MesosProtos.Port.newBuilder
      .setName("http")
      .setNumber(80)
      .setProtocol("tcp")
      .build
    val discoveryInfoProto = Protos.DiscoveryInfo.newBuilder
      .addPorts(portProto)
      .build

    proto.getGroupsList.asScala should equal(f.ipAddressWithGroupsAndLabels.groups)
    proto.getLabelsList.asScala.map(kv => kv.getKey -> kv.getValue).toMap should
      equal(f.ipAddressWithGroupsAndLabels.labels)
    proto.getDiscoveryInfo should equal(discoveryInfoProto)
  }

  test("ConstructFromProto with default proto") {
    val f = fixture()

    val defaultProto = Protos.IpAddress.newBuilder.build
    val result = IpAddress.fromProto(defaultProto)
    result should equal(f.defaultIpAddress)
  }

  test("ConstructFromProto with groups") {
    val f = fixture()
    val protoWithGroups = Protos.IpAddress.newBuilder
      .addAllGroups(Seq("foo", "bar").asJava)
      .build
    val result = IpAddress.fromProto(protoWithGroups)
    result should equal(f.ipAddressWithGroups)
  }

  test("ConstructFromProto with groups and labels") {
    val f = fixture()
    val protoWithGroupsAndLabels = Protos.IpAddress.newBuilder
      .addAllGroups(Seq("a", "b", "c").asJava)
      .addLabels(MesosProtos.Label.newBuilder.setKey("foo").setValue("bar"))
      .addLabels(MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz"))
      .build
    val result = IpAddress.fromProto(protoWithGroupsAndLabels)
    result should equal(f.ipAddressWithGroupsAndLabels)
  }

  test("ConstructFromProto with groups and labels and discovery") {
    val f = fixture()

    val portProto = MesosProtos.Port.newBuilder
      .setName("http")
      .setNumber(80)
      .setProtocol("tcp")
      .build
    val discoveryInfoProto = Protos.DiscoveryInfo.newBuilder
      .addPorts(portProto)
      .build

    val protoWithDiscovery = Protos.IpAddress.newBuilder
      .addAllGroups(Seq("a", "b", "c").asJava)
      .addLabels(MesosProtos.Label.newBuilder.setKey("foo").setValue("bar"))
      .addLabels(MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz"))
      .setDiscoveryInfo(discoveryInfoProto)
      .build
    val result = IpAddress.fromProto(protoWithDiscovery)
    result should equal(f.ipAddressWithDiscoveryInfo)
  }

  test("JSON Serialization round-trip defaultIpAddress") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.defaultIpAddress)
  }

  test("JSON Serialization round-trip ipAddressWithGroups") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithGroups)
  }

  test("JSON Serialization round-trip ipAddressWithGroupsAndLabels") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithGroupsAndLabels)
  }

  test("JSON Serialization round-trip ipAddressWithDiscoveryInfo") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithDiscoveryInfo)
  }

  private[this] def fromJson(json: String): IpAddress = {
    Json.fromJson[IpAddress](Json.parse(json)).get
  }

  test("Reading empty IpAddress from JSON") {
    val json =
      """
      {}
      """

    val readResult = fromJson(json)
    val f = fixture()
    readResult should equal(f.defaultIpAddress)
  }

  test("Reading IpAddress with groups from JSON") {
    val json =
      """
      {
        "groups": ["foo", "bar"]
      }
      """

    val readResult = fromJson(json)
    val f = fixture()
    readResult should equal(f.ipAddressWithGroups)
  }

  test("Reading complete IpAddress from JSON") {
    val json =
      """
      {
        "groups": ["a", "b", "c"],
        "labels": {
          "foo": "bar",
          "baz": "buzz"
        }
      }
      """

    val readResult = fromJson(json)
    val f = fixture()
    readResult should equal(f.ipAddressWithGroupsAndLabels)
  }

}

