package mesosphere.marathon.core.storage.store.impl

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.StateMetrics

import scala.concurrent.Future

trait TimedPersistenceStore[K, Category, Serialized] extends StateMetrics {
  self: PersistenceStore[K, Category, Serialized] =>

  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    timedWrite(self.deleteAll(k))

  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    timedRead(self.get(id))

  override def get[Id, V](
    id: Id,
    version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    timedRead(self.get(id, version))

  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] =
    timedWrite(self.store(id, v))

  override def store[Id, V](id: Id, v: V,
    version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] =
    timedWrite(self.store(id, v, version))

  override def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    timedWrite(self.deleteCurrent(k))

  override def deleteVersion[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    timedWrite(self.deleteVersion(k, version))
}
