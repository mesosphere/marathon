package mesosphere.marathon.state

import org.apache.mesos.{ Protos => MesosProtos }

// TODO (if supported in the future):
//   - user
//   - URIs
case class Command(value: String)
    extends MarathonState[MesosProtos.CommandInfo, Command] {

  def toProto: MesosProtos.CommandInfo =
    MesosProtos.CommandInfo.newBuilder
      .setValue(this.value)
      .build

  def mergeFromProto(proto: MesosProtos.CommandInfo): Command =
    Command(value = proto.getValue)

  def mergeFromProto(bytes: Array[Byte]): Command =
    mergeFromProto(MesosProtos.CommandInfo.parseFrom(bytes))

  override def version: Timestamp = Timestamp.zero
}
