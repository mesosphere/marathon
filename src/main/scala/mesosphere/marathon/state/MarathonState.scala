package mesosphere.marathon.state

import com.google.protobuf.Message

/**
 * @author Tobi Knaup
 */

trait MarathonState[T <: Message] {

  def mergeFromProto(message: T)

  def mergeFromProto(bytes: Array[Byte])

  def toProto: T

  def toProtoByteArray: Array[Byte] = toProto.toByteArray

}