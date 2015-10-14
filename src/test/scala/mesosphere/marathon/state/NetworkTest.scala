package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import org.scalatest.Matchers
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._

class NetworkTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {

    lazy val network1 = Network()

    lazy val network2 = Network(
      ipAddress = None,
      protocol = Some(mesos.NetworkInfo.Protocol.IPv6),
      groups = Vector("foo", "bar"),
      labels = Map.empty
    )

    lazy val network3 = Network(
      ipAddress = Some("2001:db8::/48"),
      protocol = Some(mesos.NetworkInfo.Protocol.IPv4),
      groups = Vector("a", "b", "c"),
      labels = Map(
        "foo" -> "bar",
        "baz" -> "buzz"
      )
    )

  }

  def fixture(): Fixture = new Fixture

  test("ToProto") {
    val f = fixture()
    val proto1 = f.network1.toProto
    // TODO(CD): make assertions!

    val proto2 = f.network2.toProto
    // TODO(CD): make assertions!

    val proto3 = f.network3.toProto
    // TODO(CD): make assertions!
  }

  test("ConstructFromProto") {
    val f = fixture()

    val networkInfo1 = mesos.NetworkInfo.newBuilder.build
    println(networkInfo1)
    val result1 = Network(networkInfo1)
    println(result1)
    println(f.network1)
    assert(result1 == f.network1)

    val networkInfo2 = mesos.NetworkInfo.newBuilder
      .setProtocol(mesos.NetworkInfo.Protocol.IPv6)
      .addAllGroups(Seq("foo", "bar").asJava)
      .build
    val result2 = Network(networkInfo2)
    assert(result2 == f.network2)

    val networkInfo3 = mesos.NetworkInfo.newBuilder
      .setIpAddress("2001:db8::/48")
      .setProtocol(mesos.NetworkInfo.Protocol.IPv4)
      .addAllGroups(Seq("a", "b", "c").asJava)
      .setLabels(
        mesos.Labels.newBuilder
          .addLabels(
            mesos.Label.newBuilder
              .setKey("foo")
              .setValue("bar")
              .build
          )
          .addLabels(
            mesos.Label.newBuilder
              .setKey("baz")
              .setValue("buzz")
              .build
          )
          .build
      )
      .build
    val result3 = Network(networkInfo3)
    assert(result3 == f.network3)
  }

  test("Serialization round-trip") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.network1)
    JsonTestHelper.assertSerializationRoundtripWorks(f.network2)
    JsonTestHelper.assertSerializationRoundtripWorks(f.network3)
  }

  private[this] def fromJson(json: String): Network = {
    Json.fromJson[Network](Json.parse(json)).get
  }

  test("Reading empty Network from JSON") {
    val json1 =
      """
      {}
      """

    val readResult = fromJson(json1)
    val f = fixture()
    assert (readResult == f.network1)
  }

  test("Reading Network with protocol and groups from JSON") {
    val json =
      """
      {
        "protocol": "IPv6",
        "groups": ["foo", "bar"]
      }
      """

    val readResult = fromJson(json)
    val f = fixture()
    assert (readResult == f.network2)
  }

  test("Reading Network from JSON") {
    val json =
      """
      {
        "ipAddress": "2001:db8::/48",
        "protocol": "IPv4",
        "groups": ["a", "b", "c"],
        "labels": {
          "foo": "bar",
          "baz": "buzz"
        }
      }
      """

    val readResult = fromJson(json)
    val f = fixture()
    assert(readResult == f.network3)
  }

}

