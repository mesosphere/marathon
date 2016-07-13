package mesosphere.marathon.core.storage.impl.memory

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller

case class RamId(category: String, id: String, version: Option[OffsetDateTime])

case class Identity(value: Any)

trait InMemoryStoreSerialization {
  implicit def marshaller[V]: Marshaller[V, Identity] = Marshaller.opaque { a: V => Identity(a) }

  implicit def unmarshaller[V]: Unmarshaller[Identity, V] =
    Unmarshaller.strict { a: Identity => a.value.asInstanceOf[V] }
}
