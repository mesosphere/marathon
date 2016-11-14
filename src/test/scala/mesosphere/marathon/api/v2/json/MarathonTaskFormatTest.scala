package mesosphere.marathon
package api.v2.json

import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{ Instance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.stream._
import mesosphere.marathon.test.MarathonSpec
import org.apache.mesos.{ Protos => MesosProtos }

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
      agentInfo = Instance.AgentInfo("agent1.mesos", Some("abcd-1234"), Seq.empty),
      runSpecVersion = time,
      status = Task.Status(
        stagedAt = time,
        startedAt = None,
        condition = Condition.Staging,
        networkInfo = NetworkInfo.empty)
    )

    def mesosStatus(taskId: Task.Id) = {
      MesosProtos.TaskStatus.newBuilder()
        .setTaskId(taskId.mesosTaskId)
        .setState(MesosProtos.TaskState.TASK_STAGING)
        .setContainerStatus(
          MesosProtos.ContainerStatus.newBuilder().addAllNetworkInfos(networkInfos)
        ).build
    }

    val taskWithMultipleIPs = {
      val taskStatus = mesosStatus(Task.Id("/foo/bar"))
      new Task.LaunchedEphemeral(
        taskId = Task.Id("/foo/bar"),
        agentInfo = Instance.AgentInfo("agent1.mesos", Some("abcd-1234"), Seq.empty),
        runSpecVersion = time,
        status = Task.Status(
          stagedAt = time,
          startedAt = None,
          mesosStatus = Some(taskStatus),
          condition = Condition.Staging,
          networkInfo = NetworkInfo.empty.update(taskStatus))
      )
    }
    val taskWithLocalVolumes = new Task.LaunchedOnReservation(
      taskId = Task.Id("/foo/bar"),
      agentInfo = Instance.AgentInfo("agent1.mesos", Some("abcd-1234"), Seq.empty),
      runSpecVersion = time,
      status = Task.Status(
        stagedAt = time,
        startedAt = Some(time),
        condition = Condition.Running,
        networkInfo = NetworkInfo.empty),
      reservation = Task.Reservation(
        Seq(Task.LocalVolumeId.unapply("appid#container#random")).flatten,
        TestTaskBuilder.Helper.taskReservationStateNew))
  }

  test("JSON serialization of a Task without IPs") {
    val f = new Fixture()
    val json =
      """
        |{
        |  "id": "/foo/bar",
        |  "host": "agent1.mesos",
        |  "state": "TASK_STAGING",
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
        |  "state": "TASK_STAGING",
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
        |  "state" : "TASK_RUNNING",
        |  "ports": [],
        |  "startedAt": "1970-01-01T00:00:01.024Z",
        |  "stagedAt": "1970-01-01T00:00:01.024Z",
        |  "version": "1970-01-01T00:00:01.024Z",
        |  "slaveId": "abcd-1234",
        |  "localVolumes": [
        |    {
        |      "runSpecId" : "/appid",
        |      "containerPath": "container",
        |      "uuid": "random",
        |      "persistenceId": "appid#container#random"
        |    }
        |  ]
        |}
      """.stripMargin
    JsonTestHelper.assertThatJsonOf(f.taskWithLocalVolumes).correspondsToJsonString(json)
  }
}
