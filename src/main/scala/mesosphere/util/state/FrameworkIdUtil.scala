package mesosphere.util.state

import mesosphere.marathon.state.{ MarathonState, Timestamp }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.FrameworkID

//TODO: move logic from FrameworkID to FrameworkId (which also implies moving this class)
case class FrameworkId(id: String) extends MarathonState[Protos.FrameworkID, FrameworkId] {
  override def mergeFromProto(message: FrameworkID): FrameworkId = {
    FrameworkId(message.getValue)
  }
  override def mergeFromProto(bytes: Array[Byte]): FrameworkId = {
    mergeFromProto(Protos.FrameworkID.parseFrom(bytes))
  }
  override def toProto: FrameworkID = {
    Protos.FrameworkID.newBuilder().setValue(id).build()
  }
  override def version: Timestamp = Timestamp.zero
}

object FrameworkId {
  def fromProto(message: FrameworkID): FrameworkId = new FrameworkId(message.getValue)
  def fromProtoBytes(bytes: Array[Byte]): FrameworkId = fromProto(Protos.FrameworkID.parseFrom(bytes))
}

