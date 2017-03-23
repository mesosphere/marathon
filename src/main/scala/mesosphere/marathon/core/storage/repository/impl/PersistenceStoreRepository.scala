package mesosphere.marathon
package core.storage.repository.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.repository.{ Repository, VersionedRepository }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }

import scala.concurrent.Future

/**
  * Default Repository of value types 'V' identified by their key 'Id'
  * that handles all default behavior for interacting with a given persistence store
  * for that value type. This allows the implicits to be hidden from the consumer of the API.
  */
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
  override def all(): Source[V, NotUsed] = ids().mapAsync(Int.MaxValue)(get).collect { case Some(x) => x }
}

/**
  * Default Repository of value types 'V' identified by their key 'Id' for Values that should be versioned.
  * that handles all default behavior for interacting with a given persistence store
  * for that value type. This allows the implicits to be hidden from the consumer of the API.
  */
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

  override def getVersions(list: Seq[(Id, OffsetDateTime)]): Source[V, NotUsed] =
    persistenceStore.getVersions(list)

  override def getVersion(id: Id, version: OffsetDateTime): Future[Option[V]] =
    persistenceStore.get(id, version)

  override def storeVersion(v: V): Future[Done] =
    persistenceStore.store(extractId(v), v, extractVersion(v))

  override def deleteCurrent(id: Id): Future[Done] =
    persistenceStore.deleteCurrent(id)
}
