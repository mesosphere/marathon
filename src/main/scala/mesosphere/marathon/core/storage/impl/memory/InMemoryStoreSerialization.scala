package mesosphere.marathon.core.storage.impl.memory

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.storage.{ IdResolver, MarathonState }
import mesosphere.marathon.state.{ AppDefinition, PathId }

case class RamId(category: String, id: String, version: Option[OffsetDateTime])

case class Identity(value: Any)

trait InMemoryStoreSerialization {
  val maxVersions = 25

  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }

  private class InMemPathIdResolver[T <: MarathonState[_]](
    val category: String,
    val maxVersions: Int = InMemoryStoreSerialization.this.maxVersions)
      extends IdResolver[PathId, T, String, RamId] {
    override def toStorageId(id: PathId, version: Option[OffsetDateTime]): RamId =
      RamId(category, id.path.mkString("_"), version)

    override def fromStorageId(key: RamId): PathId = PathId(key.id.split("_").toList, absolute = true)

    override def version(v: T): OffsetDateTime = v.version.toOffsetDateTime
  }

  implicit val inMemAppDefResolver: IdResolver[PathId, AppDefinition, String, RamId] =
    new InMemPathIdResolver[AppDefinition]("app", maxVersions)
}

object InMemoryStoreSerialization extends InMemoryStoreSerialization