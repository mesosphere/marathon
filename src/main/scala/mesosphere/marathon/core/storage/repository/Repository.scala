package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime

import akka.{ Done, NotUsed }
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }

import scala.concurrent.Future

class Repository[Id, V, K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[Id, V, C, K],
    marshaller: Marshaller[V, S],
    unmarshaller: Unmarshaller[S, V]) {
  def ids(): Source[Id, NotUsed] = persistenceStore.ids()
  def get(id: Id): Future[Option[V]] = persistenceStore.get(id)
  def delete(id: Id): Future[Done] = persistenceStore.deleteAll(id)
  def store(id: Id, v: V): Future[Done] = persistenceStore.store(id, v)
  // Assume that the underlying store can limit its own concurrency.
  def all(): Source[V, NotUsed] = ids().mapAsync(Int.MaxValue)(get).filter(_.isDefined).map(_.get)
}

class VersionedRepository[Id, V, K, C, S](
    persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[Id, V, C, K],
    marshaller: Marshaller[V, S],
    unmarshaller: Unmarshaller[S, V]) extends Repository[Id, V, K, C, S](persistenceStore) {
  def versions(id: Id): Source[OffsetDateTime, NotUsed] = persistenceStore.versions(id)
  def get(id: Id, version: OffsetDateTime): Future[Option[V]] =
    persistenceStore.get(id, version)
  def store(id: Id, v: V, version: OffsetDateTime): Future[Done] =
    persistenceStore.store(id, v, version)
}
