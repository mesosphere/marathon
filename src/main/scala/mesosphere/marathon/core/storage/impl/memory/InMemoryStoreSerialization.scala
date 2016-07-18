package mesosphere.marathon.core.storage.impl.memory

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.IdResolver
import mesosphere.marathon.state.{ AppDefinition, PathId }

case class RamId(category: String, id: String, version: Option[OffsetDateTime])

case class Identity(value: Any)

trait InMemoryStoreSerialization {
  val DefaultMaxVersions = 25

  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }

  private class InMemPathIdResolver[T](
    val category: String,
    val maxVersions: Int = DefaultMaxVersions,
    getVersion: T => OffsetDateTime)
      extends IdResolver[PathId, T, String, RamId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): RamId =
      RamId(category, id.path.mkString("_"), version)

    override def fromStorageId(key: RamId): PathId = PathId(key.id.split("_").toList, absolute = true)

    override def version(v: T): OffsetDateTime = getVersion(v)
  }

  def appDefResolver(maxVersions: Int): IdResolver[PathId, AppDefinition, String, RamId] =
    new InMemPathIdResolver[AppDefinition]("app", maxVersions, _.version.toOffsetDateTime)

  implicit val appDefResolver: IdResolver[PathId, AppDefinition, String, RamId] =
    appDefResolver(DefaultMaxVersions)

  implicit def taskResolver: IdResolver[String, MarathonTask, String, RamId] =
    new IdResolver[String, MarathonTask, String, RamId] {
      override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
        RamId(category, id, version)
      override val category: String = "task"
      override def fromStorageId(key: RamId): String = key.id
      override val maxVersions: Int = 0
      // tasks are not versioned.
      override def version(v: MarathonTask): OffsetDateTime = OffsetDateTime.MIN
    }
}

object InMemoryStoreSerialization extends InMemoryStoreSerialization
