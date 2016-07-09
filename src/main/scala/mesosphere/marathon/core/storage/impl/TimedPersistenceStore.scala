package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.storage.{IdResolver, PersistenceStore}
import mesosphere.marathon.state.StateMetrics

import scala.concurrent.Future

trait TimedPersistenceStore[K, Serialized] extends StateMetrics { self: PersistenceStore[K, Serialized] =>
  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] =
    timedWrite(self.deleteAll(k))

  override def get[Id, V](id: Id)(implicit ir: IdResolver[Id, K, V, Serialized],
                                           um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    timedRead(self.get(id))

  override def get[Id, V](id: Id,
                                   version: OffsetDateTime)(implicit
                                                            ir: IdResolver[Id, K, V, Serialized],
                                                            um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    timedRead(self.get(id, version))

  override def store[Id, V](id: Id, v: V)(implicit ir: IdResolver[Id, K, V, Serialized],
                                                   m: Marshaller[V, Serialized]): Future[Done] =
    timedWrite(self.store(id, v))

  override def store[Id, V](id: Id, v: V,
                                     version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized],
                                                              m: Marshaller[V, Serialized]): Future[Done] =
    timedWrite(self.store(id, v))

  override def delete[Id, V](k: Id,
                                      version: OffsetDateTime)(implicit
                                                               ir: IdResolver[Id, K, V, Serialized]): Future[Done] =
    timedWrite(self.delete(k, version))
}
