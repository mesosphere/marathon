package mesosphere.marathon.state

import mesosphere.marathon.api.ModelValidation
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos
import mesosphere.marathon.state.PathId._
import org.scalatest.Matchers
import org.apache.mesos.{ Protos => mesos }

class TaskFailureTest extends MarathonSpec with Matchers with ModelValidation {

  class Fixture {
    lazy val taskFailure = TaskFailure(
      appId = "/group/app".toPath,
      taskId = mesos.TaskID.newBuilder.setValue("group_app-12345").build,
      state = mesos.TaskState.TASK_FAILED,
      message = "Process exited with status [1]",
      host = "slave5.mega.co",
      version = Timestamp(1000),
      timestamp = Timestamp(2000)
    )
  }

  def fixture(): Fixture = new Fixture

  test("ToProto") {
    val f = fixture()
    val proto = f.taskFailure.toProto
    assert(proto.getAppId == f.taskFailure.appId.toString)
    assert(proto.getTaskId == f.taskFailure.taskId)
    assert(proto.getState == f.taskFailure.state)
    assert(proto.getMessage == f.taskFailure.message)
    assert(proto.getHost == f.taskFailure.host)
    assert(Timestamp(proto.getVersion) == f.taskFailure.version)
    assert(Timestamp(proto.getTimestamp) == f.taskFailure.timestamp)
  }

  test("ConstructFromProto") {
    val f = fixture()

    val proto = Protos.TaskFailure.newBuilder
      .setAppId(f.taskFailure.appId.toString)
      .setTaskId(f.taskFailure.taskId)
      .setState(f.taskFailure.state)
      .setMessage(f.taskFailure.message)
      .setHost(f.taskFailure.host)
      .setVersion(f.taskFailure.version.toString)
      .setTimestamp(f.taskFailure.timestamp.toString)
      .build

    val taskFailure = TaskFailure(proto)
    assert(taskFailure == f.taskFailure)
  }

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val f = fixture()

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val json = mapper.writeValueAsString(f.taskFailure)
    val readResult = mapper.readValue(json, classOf[TaskFailure])
    assert(readResult == f.taskFailure)
  }

}
