package mesosphere.marathon.core.storage

import akka.util.ByteString
import com.google.protobuf.{ MessageLite, Parser }

/**
  * Trait indicating the state is intended to be persisted via a Proto message.
  * @tparam Proto The type of the proto message.
  *
  * Implementations of this type support automatic serialization to
  *   [[mesosphere.marathon.core.storage.impl.zk.ZkPersistenceStore]] provided a [[IdResolver]]
  *
  * Implementations should also provide [[MarathonProto]] into their companion (and
  * implement the implicit [[mesosphere.marathon.core.storage.impl.zk.ZkSerialization.zkUnmarshaller()]]
  * for automatic deserialization of their proto type)
  */
trait MarathonState[Proto <: MessageLite] {
  def toProto: Proto
}

/**
  * Trait to be mixed into the companion (in general) allowing for automatic deserialization
  * of Proto Messages back into 'T'
  *
  * Implement the implicit [[mesosphere.marathon.core.storage.impl.zk.ZkSerialization.zkUnmarshaller()]]
  * for automatic deserialization of the prototype.
  *
  * @tparam Proto The proto type of the message
  * @tparam T The marathon type of the message
  */
trait MarathonProto[Proto <: MessageLite, T <: MarathonState[_]] {
  /**
    * The protobuf parser for the message type
    */
  protected def parser: Parser[Proto]

  def fromProto(proto: Proto): T

  private[storage] def fromProtoBytes(bytes: ByteString): T = {
    fromProto(parser.parseFrom(bytes.toArray))
  }
}
