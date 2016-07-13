package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import com.google.protobuf.MessageLite
import mesosphere.marathon.core.storage.{ MarathonProto, MarathonState }

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = id.hashCode % 16
  def path: String = version.fold(s"/$category/$bucket/$id") { v =>
    s"/$category/$bucket/$id/versions/$v"
  }
}

case class ZkSerialized(bytes: ByteString)

trait ZkSerialization {
  implicit def zkMarshal[A <: MessageLite, B <: MarathonState[A]]: Marshaller[MarathonState[A], ZkSerialized] =
    Marshaller.opaque { (a: MarathonState[A]) =>
      ZkSerialized(ByteString(a.toProto.toByteArray))
    }

  def zkUnmarshaller[A <: MessageLite, B <: MarathonState[A]](
    proto: MarathonProto[A, B]): Unmarshaller[ZkSerialized, B] =
    Unmarshaller.strict { (a: ZkSerialized) => proto.fromProtoBytes(a.bytes) }
}

object ZkSerialization extends ZkSerialization