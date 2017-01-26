package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import play.api.libs.json.Json

class TaskFailureTest extends UnitTest {
  import TaskFailureTestHelper.taskFailure

  "TaskFailure" should {
    "ToProto" in {
      val proto = taskFailure.toProto
      assert(proto.getAppId == taskFailure.appId.toString)
      assert(proto.getTaskId == taskFailure.taskId)
      assert(proto.getState == taskFailure.state)
      assert(proto.getMessage == taskFailure.message)
      assert(proto.getHost == taskFailure.host)
      assert(Timestamp(proto.getVersion) == taskFailure.version)
      assert(Timestamp(proto.getTimestamp) == taskFailure.timestamp)
    }

    "ConstructFromProto" in {
      val proto = Protos.TaskFailure.newBuilder
        .setAppId(taskFailure.appId.toString)
        .setTaskId(taskFailure.taskId)
        .setState(taskFailure.state)
        .setMessage(taskFailure.message)
        .setHost(taskFailure.host)
        .setVersion(taskFailure.version.toString)
        .setTimestamp(taskFailure.timestamp.toString)
        .build

      val taskFailureFromProto = TaskFailure(proto)
      assert(taskFailureFromProto == taskFailure)
    }

    "ConstructFromProto with SlaveID" in {
      val taskFailureFixture = taskFailure.copy(slaveId = Some(slaveIDToProto(SlaveID("slave id"))))

      val proto = Protos.TaskFailure.newBuilder
        .setAppId(taskFailureFixture.appId.toString)
        .setTaskId(taskFailureFixture.taskId)
        .setState(taskFailureFixture.state)
        .setMessage(taskFailureFixture.message)
        .setHost(taskFailureFixture.host)
        .setVersion(taskFailureFixture.version.toString)
        .setTimestamp(taskFailureFixture.timestamp.toString)
        .setSlaveId(taskFailureFixture.slaveId.get)
        .build

      val taskFailureFromProto = TaskFailure(proto)
      assert(taskFailureFromProto == taskFailureFixture)
    }

    "Json serialization" in {
      import mesosphere.marathon.api.v2.json.Formats._

      val json = Json.toJson(taskFailure.copy(slaveId = Some(slaveIDToProto(SlaveID("slave id")))))
      val expectedJson = Json.parse(
        """
        |{
        |  "appId":"/group/app",
        |  "host":"slave5.mega.co",
        |  "message":"Process exited with status [1]",
        |  "state":"TASK_FAILED",
        |  "taskId":"group_app-12345",
        |  "timestamp":"1970-01-01T00:00:02.000Z",
        |  "version":"1970-01-01T00:00:01.000Z",
        |  "slaveId":"slave id"
        |}
      """.stripMargin)
      assert(expectedJson == json)
    }

  }
}
