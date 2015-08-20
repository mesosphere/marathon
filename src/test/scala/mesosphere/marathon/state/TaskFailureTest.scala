package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos
import mesosphere.marathon.state.PathId._
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.scalatest.Matchers
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

class TaskFailureTest extends MarathonSpec with Matchers {

  lazy val taskFailure = TaskFailure(
    appId = "/group/app".toPath,
    taskId = mesos.TaskID.newBuilder.setValue("group_app-12345").build,
    state = mesos.TaskState.TASK_FAILED,
    message = "Process exited with status [1]",
    host = "slave5.mega.co",
    version = Timestamp(1000),
    timestamp = Timestamp(2000)
  )

  test("ToProto") {
    val proto = taskFailure.toProto
    assert(proto.getAppId == taskFailure.appId.toString)
    assert(proto.getTaskId == taskFailure.taskId)
    assert(proto.getState == taskFailure.state)
    assert(proto.getMessage == taskFailure.message)
    assert(proto.getHost == taskFailure.host)
    assert(Timestamp(proto.getVersion) == taskFailure.version)
    assert(Timestamp(proto.getTimestamp) == taskFailure.timestamp)
  }

  test("ConstructFromProto") {
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

  test("ConstructFromProto with SlaveID") {
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

  test("Json serialization") {
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
