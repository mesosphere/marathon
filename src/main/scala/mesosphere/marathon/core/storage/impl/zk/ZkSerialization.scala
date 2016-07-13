package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import com.google.protobuf.MessageLite
import mesosphere.marathon.core.storage.{ IdResolver, MarathonProto, MarathonState }
import mesosphere.marathon.state.{ AppDefinition, PathId }

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = id.hashCode % 16
  def path: String = version.fold(s"/$category/$bucket/$id") { v =>
    s"/$category/$bucket/$id/versions/$v"
  }
}

case class ZkSerialized(bytes: ByteString)

trait ZkSerialization {
  val maxVersions = 25

  /** Anything that implements [[MarathonState]] is automatically marshalled. */
  implicit def zkMarshal[A <: MessageLite, B <: MarathonState[A]]: Marshaller[MarathonState[A], ZkSerialized] =
    Marshaller.opaque { (a: MarathonState[A]) =>
      ZkSerialized(ByteString(a.toProto.toByteArray))
    }

  /** Anything that implements [[MarathonState]] can be unmarshalled, provided the [[MarathonProto]] */
  private def zkUnmarshaller[A <: MessageLite, B <: MarathonState[A]](
    proto: MarathonProto[A, B]): Unmarshaller[ZkSerialized, B] =
    Unmarshaller.strict { (a: ZkSerialized) => proto.fromProtoBytes(a.bytes) }

  /** General id resolver for a key of Path.Id */
  private class ZkPathIdResolver[T <: MarathonState[_]](
    val category: String,
    val maxVersions: Int = ZkSerialization.this.maxVersions)
      extends IdResolver[PathId, T, String, ZkId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): ZkId =
      ZkId(category, id.path.mkString("_"), version)
    override def fromStorageId(key: ZkId): PathId = PathId(key.id.split("_").toList, absolute = true)
    override def version(v: T): OffsetDateTime = v.version.toOffsetDateTime
  }

  implicit val zkAppDefResolver: IdResolver[PathId, AppDefinition, String, ZkId] =
    new ZkPathIdResolver[AppDefinition]("apps", maxVersions)
  implicit val zkAppDefUnmarshaller = zkUnmarshaller(AppDefinition)
}

object ZkSerialization extends ZkSerialization
