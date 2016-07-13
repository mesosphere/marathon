package mesosphere.marathon.state

import com.google.protobuf.MessageLite
import mesosphere.marathon.core.storage.{ MarathonState => MarathonIState }

trait MarathonState[M <: MessageLite, T <: MarathonState[M, _]] extends MarathonIState[M] {

  def mergeFromProto(message: M): T

  def mergeFromProto(bytes: Array[Byte]): T

  def toProto: M

  def toProtoByteArray: Array[Byte] = toProto.toByteArray

  def version: Timestamp
}
