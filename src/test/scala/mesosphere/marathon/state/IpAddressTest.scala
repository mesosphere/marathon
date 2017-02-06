package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.{ Protos => MesosProtos }
import play.api.libs.json.Json

class IpAddressTest extends UnitTest {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {

    lazy val defaultIpAddress = IpAddress()

    lazy val ipAddressWithGroups = IpAddress(
      groups = Vector("foo", "bar"),
      labels = Map.empty
    )

    lazy val ipAddressWithNetworkName = IpAddress(
      networkName = Some("foo")
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

  "IpAddress" should {
    "ToProto default IpAddress" in {
      // this is slightly artificial, since every IpAddress should have at least one group
      val f = fixture()
      val proto = f.defaultIpAddress.toProto

      // The default IpAddress has an empty DiscoveryInfo
      val defaultIpAddressProto =
        Protos.IpAddress.newBuilder().setDiscoveryInfo(Protos.DiscoveryInfo.getDefaultInstance).build()
      proto should be(defaultIpAddressProto)
    }

    "ToProto with networkName" in {
      val f = fixture()
      val proto = f.ipAddressWithNetworkName.toProto
      proto.getNetworkName should equal(f.ipAddressWithNetworkName.networkName.get)
    }

    "ToProto with groups" in {
      val f = fixture()
      val proto = f.ipAddressWithGroups.toProto
      proto.getGroupsList should contain theSameElementsAs f.ipAddressWithGroups.groups
      proto.getLabelsList should be(empty)
    }

    "ToProto with groups and labels" in {
      val f = fixture()
      val proto = f.ipAddressWithGroupsAndLabels.toProto
      proto.getGroupsList should contain theSameElementsAs f.ipAddressWithGroupsAndLabels.groups
      proto.getLabelsList.map(kv => kv.getKey -> kv.getValue) should
        contain theSameElementsAs f.ipAddressWithGroupsAndLabels.labels
    }

    "ToProto with groups and labels and discovery" in {
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

      proto.getGroupsList should contain theSameElementsAs f.ipAddressWithGroupsAndLabels.groups
      proto.getLabelsList.map(kv => kv.getKey -> kv.getValue) should
        contain theSameElementsAs f.ipAddressWithGroupsAndLabels.labels
      proto.getDiscoveryInfo should equal(discoveryInfoProto)
    }

    "ConstructFromProto with default proto" in {
      val f = fixture()

      val defaultProto = Protos.IpAddress.newBuilder.build
      val result = IpAddress.fromProto(defaultProto)
      result should equal(f.defaultIpAddress)
    }

    "ConstructFromProto with groups" in {
      val f = fixture()
      val protoWithGroups = Protos.IpAddress.newBuilder
        .addAllGroups(Seq("foo", "bar"))
        .build
      val result = IpAddress.fromProto(protoWithGroups)
      result should equal(f.ipAddressWithGroups)
    }

    "ConstructFromProto with networkName" in {
      val f = fixture()
      val proto = Protos.IpAddress.newBuilder
        .setNetworkName("foo")
        .build
      val result = IpAddress.fromProto(proto)
      result should equal(f.ipAddressWithNetworkName)
    }

    "ConstructFromProto with groups and labels" in {
      val f = fixture()
      val protoWithGroupsAndLabels = Protos.IpAddress.newBuilder
        .addAllGroups(Seq("a", "b", "c"))
        .addLabels(MesosProtos.Label.newBuilder.setKey("foo").setValue("bar"))
        .addLabels(MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz"))
        .build
      val result = IpAddress.fromProto(protoWithGroupsAndLabels)
      result should equal(f.ipAddressWithGroupsAndLabels)
    }

    "ConstructFromProto with groups and labels and discovery" in {
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
        .addAllGroups(Seq("a", "b", "c"))
        .addLabels(MesosProtos.Label.newBuilder.setKey("foo").setValue("bar"))
        .addLabels(MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz"))
        .setDiscoveryInfo(discoveryInfoProto)
        .build
      val result = IpAddress.fromProto(protoWithDiscovery)
      result should equal(f.ipAddressWithDiscoveryInfo)
    }

    "JSON Serialization round-trip defaultIpAddress" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.defaultIpAddress)
    }

    "JSON Serialization round-trip ipAddressWithGroups" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithGroups)
    }

    "JSON Serialization round-trip ipAddressWithGroupsAndLabels" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithGroupsAndLabels)
    }

    "JSON Serialization round-trip ipAddressWithDiscoveryInfo" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithDiscoveryInfo)
    }

    "JSON Serialization round-trip ipAddressWithNetworkName" in {
      val f = fixture()
      JsonTestHelper.assertSerializationRoundtripWorks(f.ipAddressWithNetworkName)
    }

    def fromJson(json: String): IpAddress = {
      Json.fromJson[IpAddress](Json.parse(json)).get
    }

    "Reading empty IpAddress from JSON" in {
      val json =
        """
      {}
      """

      val readResult = fromJson(json)
      val f = fixture()
      readResult should equal(f.defaultIpAddress)
    }

    "Reading IpAddress with groups from JSON" in {
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

    "Reading complete IpAddress from JSON" in {
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
}

