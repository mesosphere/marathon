package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._

class MarathonTaskFormatTest extends MarathonSpec {
  import Formats._

  class Fixture {
    val taskWithoutIp = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(MesosProtos.SlaveID.newBuilder.setValue("abcd-1234"))
      .build

    val ip1 = MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.123").build
    val ip2 = MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.124").build
    val taskWithMultipleIPs = MarathonTask.newBuilder
      .setId("/foo/bar")
      .setHost("agent1.mesos")
      .setVersion(Timestamp(1024).toString)
      .setStagedAt(1024L)
      .setSlaveId(MesosProtos.SlaveID.newBuilder.setValue("abcd-1234"))
      .addAllNetworks(
        Seq(
          MesosProtos.NetworkInfo.newBuilder.addIpAddresses(ip1).build,
          MesosProtos.NetworkInfo.newBuilder.addIpAddresses(ip2).build
        ).asJava
      ).build
  }

  test("JSON serialization of MarathonTask without IPs") {
    val f = new Fixture()
    val json =
      """
        |{
        |  "id": "/foo/bar",
        |  "host": "agent1.mesos",
        |  "ipAddresses": [],
        |  "ports": [],
        |  "startedAt": null,
        |  "stagedAt": "1970-01-01T00:00:01.024Z",
        |  "version": "1970-01-01T00:00:01.024Z",
        |  "slaveId": "abcd-1234"
        |}
      """.stripMargin
    JsonTestHelper.assertThatJsonOf(f.taskWithoutIp).correspondsToJsonString(json)
  }

  test("JSON serialization of MarathonTask with multiple IPs") {
    val f = new Fixture()
    val json =
      """
        |{
        |  "id": "/foo/bar",
        |  "host": "agent1.mesos",
        |  "ipAddresses": [
        |    {
        |      "ipAddress": "123.123.123.123",
        |      "protocol": "IPv4"
        |    },
        |    {
        |      "ipAddress": "123.123.123.124",
        |      "protocol": "IPv4"
        |    }
        |  ],
        |  "ports": [],
        |  "startedAt": null,
        |  "stagedAt": "1970-01-01T00:00:01.024Z",
        |  "version": "1970-01-01T00:00:01.024Z",
        |  "slaveId": "abcd-1234"
        |}
      """.stripMargin
    JsonTestHelper.assertThatJsonOf(f.taskWithMultipleIPs).correspondsToJsonString(json)
  }
}
