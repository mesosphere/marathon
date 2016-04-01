package mesosphere.marathon.api.v2.json

import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec }
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.immutable.Seq

class MarathonTaskFormatTest extends MarathonSpec {
  import Formats._

  class Fixture {
    val time = Timestamp(1024)
    val networkInfos = Seq(
      MesosProtos.NetworkInfo.newBuilder()
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.123"))
        .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("123.123.123.124"))
        .build()
    )

    val taskWithoutIp = new Task.LaunchedEphemeral(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Task.AgentInfo("agent1.mesos", Some("abcd-1234"), Iterable.empty),
      appVersion = time,
      status = Task.Status(time, None),
      hostPorts = Seq.empty)

    import scala.collection.JavaConverters._
    val taskWithMultipleIPs = new Task.LaunchedEphemeral(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Task.AgentInfo("agent1.mesos", Some("abcd-1234"), Iterable.empty),
      appVersion = time,
      status = Task.Status(
        stagedAt = time,
        startedAt = None,
        mesosStatus = Some(
          MesosProtos.TaskStatus.newBuilder()
            .setTaskId(Task.Id("/foo/bar").mesosTaskId)
            .setState(MesosProtos.TaskState.TASK_RUNNING)
            .setContainerStatus(
              MesosProtos.ContainerStatus.newBuilder().addAllNetworkInfos(networkInfos.asJava)
            ).build
        )),
      hostPorts = Seq.empty
    )

    val taskWithLocalVolumes = new Task.LaunchedOnReservation(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Task.AgentInfo("agent1.mesos", Some("abcd-1234"), Iterable.empty),
      appVersion = time,
      status = Task.Status(time, Some(time)),
      hostPorts = Seq.empty,
      reservation = Task.Reservation(
        Seq(Task.LocalVolumeId.unapply("appid#container#random")).flatten,
        MarathonTestHelper.taskReservationStateNew))
  }

  test("JSON serialization of a Task without IPs") {
    val f = new Fixture()
    val json =
      """
        |{
        |  "id": "/foo/bar",
        |  "host": "agent1.mesos",
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
