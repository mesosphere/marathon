package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.state.StateMetrics

import scala.concurrent.Future

trait TimedPersistenceStore[K, Category, Serialized] extends StateMetrics {
  self: PersistenceStore[K, Category, Serialized] =>

  override def deleteAll[Id, V](k: Id)(implicit ir: Resolver[Id, V]): Future[Done] =
    timedWrite(self.deleteAll(k))

  override def get[Id, V](id: Id)(implicit
    ir: Resolver[Id, V],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    timedRead(self.get(id))

  override def get[Id, V](
    id: Id,
    version: OffsetDateTime)(implicit
    ir: Resolver[Id, V],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    timedRead(self.get(id, version))

  override def store[Id, V](id: Id, v: V)(implicit
    ir: Resolver[Id, V],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] =
    timedWrite(self.store(id, v))

  override def store[Id, V](id: Id, v: V,
    version: OffsetDateTime)(implicit
    ir: Resolver[Id, V],
    m: Marshaller[V, Serialized]): Future[Done] =
    timedWrite(self.store(id, v, version))

  override def delete[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: Resolver[Id, V]): Future[Done] =
    timedWrite(self.delete(k, version))
}
