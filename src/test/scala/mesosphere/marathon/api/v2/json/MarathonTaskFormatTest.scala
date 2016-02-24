package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.{ ReservationWithVolumes, NetworkInfoList, NoNetworking }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => MesosProtos }

class MarathonTaskFormatTest extends MarathonSpec {
  import Formats._

  class Fixture {
    val time = Timestamp(1024)
    val network = MesosProtos.NetworkInfo.newBuilder()
      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.123"))
      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.124"))
      .build()

    val taskWithoutIp = new Task(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Task.AgentInfo("agent1.mesos", Some("abcd-1234"), Iterable.empty),
      reservationWithVolumes = None,
      launched = Some(Task.Launched(time, Task.Status(time, None), NoNetworking)))

    val taskWithMultipleIPs = new Task(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Task.AgentInfo("agent1.mesos", Some("abcd-1234"), Iterable.empty),
      reservationWithVolumes = None,
      launched = Some(Task.Launched(time, Task.Status(time, None), NetworkInfoList(network))))

    val taskWithLocalVolumes = new Task(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Task.AgentInfo("agent1.mesos", Some("abcd-1234"), Iterable.empty),
      reservationWithVolumes = Some(ReservationWithVolumes(Seq(Task.LocalVolumeId.unapply("appid#container#random")).flatten)),
      launched = Some(Task.Launched(time, Task.Status(time, Some(time)), NoNetworking)))
  }

  test("JSON serialization of a Task without IPs") {
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

  test("JSON serialization of a Task with multiple IPs") {
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

  test("JSON serialization of a Task with reserved local volumes") {
    val f = new Fixture()
    val json =
      """
        |{
        |  "id": "/foo/bar",
        |  "host": "agent1.mesos",
        |  "ipAddresses": [],
        |  "ports": [],
        |  "startedAt": "1970-01-01T00:00:01.024Z",
        |  "stagedAt": "1970-01-01T00:00:01.024Z",
        |  "version": "1970-01-01T00:00:01.024Z",
        |  "slaveId": "abcd-1234",
        |  "localVolumes": [
        |    {
        |      "containerPath": "container",
        |      "persistenceId": "appid#container#random"
        |    }
        |  ]
        |}
      """.stripMargin
    JsonTestHelper.assertThatJsonOf(f.taskWithLocalVolumes).correspondsToJsonString(json)
  }
}
