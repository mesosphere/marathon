package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.{BasePersistenceStore, IdResolver, PersistenceStore, VersionedId}
import mesosphere.marathon.util.toRichFuture

import scala.async.Async.{async, await}
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * A Write Ahead Cache of another persistence store that lazily loads values into the cache.
  *
  * @param store The store to cache
  * @param mat a materializer for Akka Streaming
  * @param ctx The execution context for future chaining.
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
class LazyCachingPersistenceStore[K, Serialized](
    store: BasePersistenceStore[K, Serialized])(implicit
  mat: ActorMaterializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Serialized] with StrictLogging {

  private[storage] val idCache = TrieMap[K, Seq[Any]]()
  private[storage] val valueCache = TrieMap[K, Option[Any]]()

  override def ids[Id, V]()(implicit ir: IdResolver[Id, K, V, Serialized]): Source[Id, NotUsed] = {
    val storeId = ir.toStorageCategory
    if (idCache.contains(storeId)) {
      Source(idCache(storeId).map(_.asInstanceOf[Id]))
    } else {
      val childFuture = async {
        val children = await(store.ids.toMat(Sink.seq)(Keep.right).run())
        idCache(storeId) = children
        children
      }
      Source.fromFuture(childFuture).mapConcat(identity)
    }
  }

  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] = {
    async {
      await(store.deleteAll(k))
      val old = idCache.getOrElse(ir.toStorageCategory, Nil)
      idCache.put(ir.toStorageCategory, old.filter(_ == ir.toStorageId(k, None)))
      Done
    }
  }

  override def get[Id, V](id: Id)(implicit ir: IdResolver[Id, K, V, Serialized],
                                  um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    val storageId = ir.toStorageId(id, None)
    val cached = valueCache.get(storageId)
    cached match {
      case Some(v) =>
        Future.successful(v.asInstanceOf[Option[V]])
      case None =>
        async {
          val value = await(store.get(id))
          valueCache.put(storageId, value)
          value
        }
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized],
                                                         um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    store.get(id, version)

  override def store[Id, V](id: Id, v: V)(implicit ir: IdResolver[Id, K, V, Serialized],
                                          m: Marshaller[V, Serialized]): Future[Done] = {
    async {
      await(store.store(id, v))
      val storageId = ir.toStorageId(id, None)
      val storageCategory = ir.toStorageCategory
      valueCache.put(storageId, Some(v))
      val cachedIds = idCache.getOrElse(storageCategory, Nil)
      idCache.put(storageCategory, storageId +: cachedIds)
      Done
    }
  }

  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized],
                                                                   m: Marshaller[V, Serialized]): Future[Done] =
    store.store(id, v, version)

  override def versions[Id, V](id: Id)(implicit
                                       ir: IdResolver[Id, K, V, Serialized]): Source[VersionedId[Id], NotUsed] =
    store.versions(id)

  override def delete[Id, V](k: Id,
                             version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] =
    store.delete(k, version)

  override protected[storage] def keys(): Source[K, NotUsed] = {
    logger.warn(s"keys() called on a CachingPersistenceStore (which is what keys() is for), deferring to $store")
    store.keys()
  }

  override def toString: String = s"LazyCachingPersistenceStore($store)"
}
