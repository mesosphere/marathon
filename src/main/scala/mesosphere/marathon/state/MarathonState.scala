package mesosphere.marathon.state

import com.google.protobuf.Message

/**
 * @author Tobi Knaup
 */

trait MarathonState[M <: Message, T <: MarathonState[M, _]] {

  def mergeFromProto(message: M): T

  def mergeFromProto(bytes: Array[Byte]): T

  def toProto: M

  def toProtoByteArray: Array[Byte] = toProto.toByteArray

}
