package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.core.storage.IdResolver
import mesosphere.marathon.state.{ AppDefinition, PathId }

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = math.abs(id.hashCode % 16)
  def path: String = version.fold(f"/$category/$bucket%x/$id") { v =>
    f"/$category/$bucket%x/$id/versions/$v"
  }
}

case class ZkSerialized(bytes: ByteString)

trait ZkStoreSerialization {
  val DefaultMaxVersions = 25

  /** General id resolver for a key of Path.Id */
  private class ZkPathIdResolver[T](
    val category: String,
    val maxVersions: Int = DefaultMaxVersions,
    getVersion: (T) => OffsetDateTime)
      extends IdResolver[PathId, T, String, ZkId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): ZkId =
      ZkId(category, id.path.mkString("_"), version)
    override def fromStorageId(key: ZkId): PathId = PathId(key.id.split("_").toList, absolute = true)
    override def version(v: T): OffsetDateTime = getVersion(v)
  }

  def appDefResolver(maxVersions: Int): IdResolver[PathId, AppDefinition, String, ZkId] =
    new ZkPathIdResolver[AppDefinition]("apps", maxVersions, _.version.toOffsetDateTime)
  implicit val appDefResolver: IdResolver[PathId, AppDefinition, String, ZkId] =
    appDefResolver(DefaultMaxVersions)

  implicit val appDefMarshaller: Marshaller[AppDefinition, ZkSerialized] =
    Marshaller.opaque(appDef => ZkSerialized(ByteString(appDef.toProtoByteArray)))

  implicit val appDefUnmarshaller: Unmarshaller[ZkSerialized, AppDefinition] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        val proto = ServiceDefinition.PARSER.parseFrom(byteString.toArray)
        AppDefinition.fromProto(proto)
    }
}

object ZkStoreSerialization extends ZkStoreSerialization
