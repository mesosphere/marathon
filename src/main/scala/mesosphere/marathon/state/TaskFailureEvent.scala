package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.ServiceDefinition

case class TaskFailureEvent(
    appId: PathId = null,
    message: String = "",
    eventType: String = "task_failure_event",
    timestamp: String = Timestamp.now().toString) extends MarathonState[Protos.TaskFailureEvent, TaskFailureEvent] {

  override def mergeFromProto(message: Protos.TaskFailureEvent): TaskFailureEvent = ???

  override def mergeFromProto(bytes: Array[Byte]): TaskFailureEvent = ???

  override def toProto: Protos.TaskFailureEvent = ???
}
