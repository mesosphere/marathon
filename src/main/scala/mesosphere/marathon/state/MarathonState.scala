package mesosphere.marathon
package state

import com.google.protobuf.MessageLite

trait MarathonState[M <: MessageLite, T <: MarathonState[M, _]] {

  def mergeFromProto(message: M): T

  def mergeFromProto(bytes: Array[Byte]): T

  def toProto: M

  def toProtoByteArray: Array[Byte] = toProto.toByteArray

  def version: Timestamp
}
