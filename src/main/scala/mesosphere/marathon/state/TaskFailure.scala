package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.state.PathId._
import org.apache.mesos.{ Protos => mesos }

case class TaskFailure(
  appId: PathId,
  taskId: mesos.TaskID,
  state: mesos.TaskState,
  message: String = "",
  host: String = "",
  version: Timestamp = Timestamp.now,
  timestamp: Timestamp = Timestamp.now)
    extends MarathonState[Protos.TaskFailure, TaskFailure] {

  override def mergeFromProto(proto: Protos.TaskFailure): TaskFailure =
    TaskFailure(proto)

  override def mergeFromProto(bytes: Array[Byte]): TaskFailure = {
    val proto = Protos.TaskFailure.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.TaskFailure =
    Protos.TaskFailure.newBuilder
      .setAppId(appId.toString)
      .setTaskId(taskId)
      .setState(state)
      .setMessage(message)
      .setHost(host)
      .setVersion(version.toString)
      .setTimestamp(timestamp.toString)
      .build

}

object TaskFailure {

  import mesosphere.marathon.event.MesosStatusUpdateEvent

  def apply(proto: Protos.TaskFailure): TaskFailure =
    TaskFailure(
      appId = proto.getAppId.toPath,
      taskId = proto.getTaskId,
      state = proto.getState,
      message = proto.getMessage,
      host = proto.getHost,
      version = Timestamp(proto.getVersion),
      timestamp = Timestamp(proto.getTimestamp)
    )

  object FromMesosStatusUpdateEvent {
    def unapply(statusUpdate: MesosStatusUpdateEvent): Option[TaskFailure] =
      apply(statusUpdate)

    def apply(statusUpdate: MesosStatusUpdateEvent): Option[TaskFailure] = {
      val MesosStatusUpdateEvent(
        slaveId, taskId, taskStateStr, message,
        appId, host, _, version, _, ts
        ) = statusUpdate

      val state = taskState(taskStateStr)

      if (isFailureState(state))
        Some(TaskFailure(
          appId,
          mesos.TaskID.newBuilder.setValue(taskId).build,
          state,
          message,
          host,
          Timestamp(version),
          Timestamp(ts)
        ))
      else None
    }
  }

  protected[this] def taskState(s: String): mesos.TaskState =
    mesos.TaskState.valueOf(s)

  protected[this] def isFailureState(state: mesos.TaskState): Boolean =
    state match {
      case mesos.TaskState.TASK_FAILED | mesos.TaskState.TASK_LOST => true
      case _ => false
    }

}
