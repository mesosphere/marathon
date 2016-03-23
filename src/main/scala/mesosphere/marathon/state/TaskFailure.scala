package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.event.UnhealthyTaskKillEvent
import mesosphere.marathon.state.PathId._
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID
import org.apache.mesos.{ Protos => mesos }

case class TaskFailure(
  appId: PathId,
  taskId: mesos.TaskID,
  state: mesos.TaskState,
  message: String = "",
  host: String = "",
  version: Timestamp = Timestamp.now,
  timestamp: Timestamp = Timestamp.now,
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
    if (slaveId.isDefined) {
      taskFailureBuilder.setSlaveId(slaveId.get)
    }
    taskFailureBuilder.build
  }
}

object TaskFailure {

  import mesosphere.marathon.event.MesosStatusUpdateEvent

  def empty: TaskFailure = {
    TaskFailure(
      PathId.empty,
      mesos.TaskID.newBuilder().setValue("").build,
      mesos.TaskState.TASK_STAGING
    )
  }

  def apply(proto: Protos.TaskFailure): TaskFailure =
    TaskFailure(
      appId = proto.getAppId.toPath,
      taskId = proto.getTaskId,
      state = proto.getState,
      message = proto.getMessage,
      host = proto.getHost,
      version = Timestamp(proto.getVersion),
      timestamp = Timestamp(proto.getTimestamp),
      slaveId = if (proto.hasSlaveId) Some(proto.getSlaveId) else None
    )

  object FromUnhealthyTaskKillEvent {
    def unapply(event: UnhealthyTaskKillEvent): Option[TaskFailure] =
      Some(apply(event))

    def apply(event: UnhealthyTaskKillEvent): TaskFailure = {
      val UnhealthyTaskKillEvent(appId, taskId, version, reason, host, slaveID, _, timestamp) = event

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
        slaveId, taskId, taskStateStr, message,
        appId, host, _, _, version, _, ts
        ) = statusUpdate

      val state = taskState(taskStateStr)

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

  protected[this] def isFailureState(state: mesos.TaskState): Boolean = {
    import mesos.TaskState._
    state match {
      case TASK_FAILED | TASK_LOST | TASK_ERROR => true
      case _                                    => false
    }
  }
}
