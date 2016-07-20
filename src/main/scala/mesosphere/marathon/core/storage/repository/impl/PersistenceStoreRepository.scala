package mesosphere.marathon.core.storage.repository.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.{ Repository, VersionedRepository }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }

import scala.concurrent.Future

class PersistenceStoreRepository[Id, V, K, C, S](
    persistenceStore: PersistenceStore[K, C, S],
    extractId: V => Id)(implicit
  ir: IdResolver[Id, V, C, K],
    marshaller: Marshaller[V, S],
    unmarshaller: Unmarshaller[S, V]) extends Repository[Id, V] {

  override def ids(): Source[Id, NotUsed] = persistenceStore.ids()

  override def get(id: Id): Future[Option[V]] = persistenceStore.get(id)

  override def delete(id: Id): Future[Done] = persistenceStore.deleteAll(id)

  override def store(v: V): Future[Done] = persistenceStore.store(extractId(v), v)

  // Assume that the underlying store can limit its own concurrency.
  override def all(): Source[V, NotUsed] = ids().mapAsync(Int.MaxValue)(get).filter(_.isDefined).map(_.get)
}

class PersistenceStoreVersionedRepository[Id, V, K, C, S](
  persistenceStore: PersistenceStore[K, C, S],
  extractId: V => Id,
  extractVersion: V => OffsetDateTime)(implicit
  ir: IdResolver[Id, V, C, K],
  marshaller: Marshaller[V, S],
  unmarshaller: Unmarshaller[S, V]) extends PersistenceStoreRepository[Id, V, K, C, S](
  persistenceStore,
  extractId) with VersionedRepository[Id, V] {

  override def versions(id: Id): Source[OffsetDateTime, NotUsed] = persistenceStore.versions(id)

  override def getVersion(id: Id, version: OffsetDateTime): Future[Option[V]] =
    persistenceStore.get(id, version)

  override def storeVersion(v: V): Future[Done] =
    persistenceStore.store(extractId(v), v, extractVersion(v))

  override def deleteCurrent(id: Id): Future[Done] =
    persistenceStore.deleteCurrent(id)
}
