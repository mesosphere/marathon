package mesosphere.marathon.state

import mesosphere.marathon.Protos.MarathonTask

/**
  * MarathonTask is a generated final class. In order to be able to persist MarathonTasks via an EntityStore, we need
  * something that extends MarathonState – this wrapper enables this.
  */
case class MarathonTaskState(task: MarathonTask) extends MarathonState[MarathonTask, MarathonTaskState] {

  override def mergeFromProto(message: MarathonTask): MarathonTaskState =
    MarathonTaskState(message)

  override def mergeFromProto(bytes: Array[Byte]): MarathonTaskState =
    MarathonTaskState(MarathonTask.parseFrom(bytes))

  override def toProto: MarathonTask = task

  override def version: Timestamp = Timestamp.zero
}
