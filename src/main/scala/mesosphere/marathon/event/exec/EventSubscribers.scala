package mesosphere.marathon.event.exec

import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.Protos
import collection.JavaConversions._

import mesosphere.marathon.api.validation.FieldConstraints.FieldJsonProperty

case class EventSubscribers(
  @FieldJsonProperty("execCmds")
  cmds: Set[String] = Set.empty[String]
) extends MarathonState[Protos.EventSubscribers, EventSubscribers]{

  override def mergeFromProto(message: Protos.EventSubscribers): EventSubscribers =
    EventSubscribers(Set(message.getExecCmdsList: _*))

  override def mergeFromProto(bytes: Array[Byte]): EventSubscribers = {
    val proto = Protos.EventSubscribers.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.EventSubscribers = {
    val builder = Protos.EventSubscribers.newBuilder()
    cmds.foreach(builder.addExecCmds(_))
    builder.build()
  }
}
