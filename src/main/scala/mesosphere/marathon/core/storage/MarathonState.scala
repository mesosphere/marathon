package mesosphere.marathon.core.storage

import akka.util.ByteString
import com.google.protobuf.{ MessageLite, Parser }
import mesosphere.marathon.state.Timestamp

trait MarathonState[Proto <: MessageLite] {
  def toProto: Proto

  // TODO: This should ideally be a 'val'
  def version: Timestamp
}

trait MarathonProto[Proto <: MessageLite, T <: MarathonState[_]] {
  protected def parser: Parser[Proto]

  def fromProto(proto: Proto): T

  private[storage] def fromProtoBytes(bytes: ByteString): T = {
    fromProto(parser.parseFrom(bytes.toArray))
  }
}
