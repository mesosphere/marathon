package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.{ CategorizedKey, PersistenceStore }
import mesosphere.marathon.util.RwLock

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }

/**
  * A Write Ahead Cache of another persistence store that lazily loads values into the cache.
  *
  * @param store The store to cache
  * @param mat a materializer for Akka Streaming
  * @param ctx The execution context for future chaining.
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
class LazyCachingPersistenceStore[K, Category, Serialized](
    store: BasePersistenceStore[K, Category, Serialized])(implicit
  mat: ActorMaterializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Category, Serialized] with StrictLogging {

  private[storage] val idCache = RwLock(TrieMap[Category, Seq[Any]]())
  private[storage] val valueCache = RwLock(TrieMap[K, Option[Any]]())

  override def ids[Id, V]()(implicit ir: Resolver[Id, V]): Source[Id, NotUsed] = {
    val storeId = ir.category
    idCache.read { ids =>
      if (ids.contains(storeId)) {
        Source(ids(storeId).map(_.asInstanceOf[Id]))
      } else {
        val childFuture = async {
          val children = await(store.ids.toMat(Sink.seq)(Keep.right).run())
          idCache.write(c => c(storeId) = children)
          children
        }
        Source.fromFuture(childFuture).mapConcat(identity)
      }
    }
  }

  override def deleteAll[Id, V](k: Id)(implicit ir: Resolver[Id, V]): Future[Done] = {
    async {
      await(store.deleteAll(k))
      val storageId = ir.toStorageId(k, None)
      valueCache.write { vc =>
        vc.remove(storageId)

        idCache.write { c =>
          val old = c.getOrElse(ir.category, Nil)
          val children = old.filter(_ != storageId)
          if (children.nonEmpty) {
            c.put(ir.category, children)
          } else {
            c.remove(ir.category)
          }

        }
      }
      Done
    }
  }

  override def get[Id, V](id: Id)(implicit
    ir: Resolver[Id, V],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    val storageId = ir.toStorageId(id, None)
    val cached = valueCache.read(_.get(storageId))
    cached match {
      case Some(v) =>
        Future.successful(v.asInstanceOf[Option[V]])
      case None =>
        async {
          val value = await(store.get(id))
          valueCache.write(_.put(storageId, value))
          value
        }
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: Resolver[Id, V],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    store.get(id, version)

  override def store[Id, V](id: Id, v: V)(implicit
    ir: Resolver[Id, V],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] = {
    async {
      await(store.store(id, v))
      val storageId = ir.toStorageId(id, None)
      val storageCategory = ir.category
      valueCache.write { values =>
        idCache.write { ids =>
          values.put(storageId, Some(v))
          val cachedIds = ids.getOrElse(storageCategory, Nil)
          ids.put(storageCategory, id +: cachedIds)
        }
      }
      Done
    }
  }

  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit
    ir: Resolver[Id, V],
    m: Marshaller[V, Serialized]): Future[Done] = async {
    await(store.store(id, v, version))
    valueCache.write { vc =>
      vc.putIfAbsent(ir.toStorageId(id, None), Some(v))
      idCache.write { idc =>
        val old = idc.getOrElse(ir.category, Nil)
        idc.put(ir.category, id +: old)
      }
    }
    Done
  }

  override def versions[Id, V](id: Id)(implicit ir: Resolver[Id, V]): Source[OffsetDateTime, NotUsed] =
    store.versions(id)

  override def delete[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: Resolver[Id, V]): Future[Done] =
    store.delete(k, version)

  override protected[storage] def keys(): Source[CategorizedKey[Category, K], NotUsed] = {
    logger.warn(s"keys() called on a CachingPersistenceStore (which is what keys() is for), deferring to $store")
    store.keys()
  }

  override def toString: String = s"LazyCachingPersistenceStore($store)"
}
