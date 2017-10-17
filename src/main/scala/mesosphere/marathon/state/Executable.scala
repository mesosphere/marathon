package mesosphere.marathon
package state

import org.apache.mesos.{ Protos => MesosProtos }
import mesosphere.marathon.stream.Implicits._

sealed trait Executable

object Executable {

  def mergeFromProto(proto: MesosProtos.CommandInfo): Executable =
    if (proto.getShell) Command("").mergeFromProto(proto)
    else ArgvList(Seq.empty).mergeFromProto(proto)

  def toProto(e: Executable): MesosProtos.CommandInfo = e match {
    case c: Command => c.toProto
    case a: ArgvList => a.toProto
  }
}

// TODO (if supported in the future):
//   - user
//   - URIs
case class Command(value: String) extends Executable with MarathonState[MesosProtos.CommandInfo, Command] {

  def toProto: MesosProtos.CommandInfo =
    MesosProtos.CommandInfo.newBuilder
      .setShell(true)
      .setValue(this.value)
      .build()

  def mergeFromProto(proto: MesosProtos.CommandInfo): Command =
    Command(value = proto.getValue)

  def mergeFromProto(bytes: Array[Byte]): Command =
    mergeFromProto(MesosProtos.CommandInfo.parseFrom(bytes))

  override def version: Timestamp = Timestamp.zero
}

case class ArgvList(value: Seq[String]) extends Executable with MarathonState[MesosProtos.CommandInfo, ArgvList] {

  def toProto: MesosProtos.CommandInfo = {
    val builder = MesosProtos.CommandInfo.newBuilder
      .setShell(false)
      .addAllArguments(value.asJava)

    value.headOption.foreach(builder.setValue)
    builder.build()
  }

  def mergeFromProto(proto: MesosProtos.CommandInfo): ArgvList =
    ArgvList(value = proto.getArgumentsList.toSeq)

  def mergeFromProto(bytes: Array[Byte]): ArgvList =
    mergeFromProto(MesosProtos.CommandInfo.parseFrom(bytes))

  override def version: Timestamp = Timestamp.zero
}
