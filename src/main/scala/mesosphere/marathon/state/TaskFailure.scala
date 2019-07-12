package mesosphere.marathon
package state

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import mesosphere.marathon.api.v2.json.JacksonSerializable
import mesosphere.marathon.core.event.UnhealthyInstanceKillEvent
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.apache.mesos.{Protos => mesos}

case class TaskFailure(
    appId: PathId,
    taskId: mesos.TaskID,
    state: mesos.TaskState,
    message: String = "",
    host: String = "",
    version: Timestamp = Timestamp.now(),
    timestamp: Timestamp = Timestamp.now(),
    slaveId: Option[mesos.SlaveID] = None)
  extends MarathonState[Protos.TaskFailure, TaskFailure] {

  override def mergeFromProto(proto: Protos.TaskFailure): TaskFailure =
    TaskFailure(proto)

  override def mergeFromProto(bytes: Array[Byte]): TaskFailure = {
    val proto = Protos.TaskFailure.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.TaskFailure = {
    val taskFailureBuilder = Protos.TaskFailure.newBuilder
      .setAppId(appId.toString)
      .setTaskId(taskId)
      .setState(state)
      .setMessage(message)
      .setHost(host)
      .setVersion(version.toString)
      .setTimestamp(timestamp.toString)
    slaveId.foreach(taskFailureBuilder.setSlaveId)
    taskFailureBuilder.build
  }

}

object TaskFailure extends JacksonSerializable[TaskFailure] {

  import mesosphere.marathon.core.event.MesosStatusUpdateEvent

  def empty: TaskFailure = {
    TaskFailure(
      PathId.empty,
      mesos.TaskID.newBuilder().setValue("").build,
      mesos.TaskState.TASK_STAGING
    )
  }

  def apply(proto: Protos.TaskFailure): TaskFailure =
    TaskFailure(
      appId = PathId(proto.getAppId),
      taskId = proto.getTaskId,
      state = proto.getState,
      message = proto.getMessage,
      host = proto.getHost,
      version = Timestamp(proto.getVersion),
      timestamp = Timestamp(proto.getTimestamp),
      slaveId = if (proto.hasSlaveId) Some(proto.getSlaveId) else None
    )

  object FromUnhealthyInstanceKillEvent {
    def unapply(event: UnhealthyInstanceKillEvent): Option[TaskFailure] =
      Some(apply(event))

    def apply(event: UnhealthyInstanceKillEvent): TaskFailure = {
      val UnhealthyInstanceKillEvent(appId, taskId, _, version, reason, host, slaveID, _, timestamp) = event

      TaskFailure(
        appId,
        taskId.mesosTaskId,
        mesos.TaskState.TASK_KILLED,
        s"Task was killed since health check failed. Reason: $reason",
        host,
        version,
        Timestamp(timestamp),
        slaveID.map(id => slaveIDToProto(SlaveID(id)))
      )
    }
  }

  object FromMesosStatusUpdateEvent {
    def unapply(statusUpdate: MesosStatusUpdateEvent): Option[TaskFailure] =
      apply(statusUpdate)

    def apply(statusUpdate: MesosStatusUpdateEvent): Option[TaskFailure] = {
      val MesosStatusUpdateEvent(
        slaveId, taskId, state, message,
        appId, host, _, _, version, _, ts
        ) = statusUpdate

      if (isFailureState(state))
        Some(TaskFailure(
          appId,
          taskId.mesosTaskId,
          state,
          message,
          host,
          Timestamp(version),
          Timestamp(ts),
          Option(slaveIDToProto(SlaveID(slaveId)))
        ))
      else None
    }
  }

  protected[this] def taskState(s: String): mesos.TaskState =
    mesos.TaskState.valueOf(s)

  // Note that this will also store taskFailures for TASK_LOST no matter the reason
  // TODO(PODS): this must be aligned with general state handling
  private[this] def isFailureState(state: mesos.TaskState): Boolean = {
    import mesos.TaskState._
    state match {
      case TASK_FAILED | TASK_ERROR |
        TASK_LOST | TASK_DROPPED | TASK_GONE | TASK_GONE_BY_OPERATOR | TASK_UNKNOWN => true
      case _ => false
    }
  }

  override def serializeWithJackson(value: TaskFailure, gen: JsonGenerator, provider: SerializerProvider): Unit = {
    println("Serialize TaskFAilure: " + value)
    gen.writeStartObject()
    gen.writeObjectField("appId", value.appId)
    gen.writeObjectField("host", value.host)
    gen.writeObjectField("message", value.message)
    gen.writeObjectField("state", value.state.name())
    gen.writeObjectField("taskId", value.taskId.getValue)
    gen.writeObjectField("timestamp", value.timestamp)
    gen.writeObjectField("version", value.version)
    gen.writeObjectField("slaveId", value.slaveId.map(_.getValue).orNull)
    gen.writeEndObject()

    //    Json.obj(
    //      "appId" -> failure.appId,
    //      "host" -> failure.host,
    //      "message" -> failure.message,
    //      "state" -> failure.state.name(),
    //      "taskId" -> failure.taskId.getValue,
    //      "timestamp" -> failure.timestamp,
    //      "version" -> failure.version,
    //      "slaveId" -> failure.slaveId.fold[JsValue](JsNull){ slaveId => JsString(slaveId.getValue) }
    //    )
  }
}
