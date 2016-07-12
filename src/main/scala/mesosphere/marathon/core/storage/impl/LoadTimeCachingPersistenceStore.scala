package mesosphere.marathon.core.storage.impl

import java.io.NotActiveException
import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.PrePostDriverCallback
import mesosphere.marathon.core.storage.{ CategorizedKey, PersistenceStore }
import mesosphere.marathon.util.RwLock

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future, Promise }

/**
  * A Write Ahead Cache of another persistence store that preloads the entire persistence store into memory before
  * satisfying any requests.
  *
  * TODO: Consider an alternative strategy where we see if the promise is complete and use it
  * otherwise going directly to the storage layer. This turns out to be much more complicated
  * as the cache is populated asynchronously, so it would have to queue up all create/update operations
  * onto the future to keep the value fully updated: then, there would be a short window of time when
  * the cached data is actually stale.
  *
  * @param store The store to cache
  * @param mat a materializer for akka streaming
  * @param ctx The execution context for future chaining.
  * @tparam Serialized The serialized format for the persistence store.
  */
class LoadTimeCachingPersistenceStore[K, Category, Serialized](
    store: BasePersistenceStore[K, Category, Serialized],
    maxPreloadRequests: Int = 8)(
    implicit
    mat: Materializer,
    ctx: ExecutionContext
) extends PersistenceStore[K, Category, Serialized] with StrictLogging with PrePostDriverCallback {

  private[storage] var idCache: Future[RwLock[TrieMap[Category, Seq[K]]]] = Future.failed(new NotActiveException())
  private[storage] var cache: Future[RwLock[TrieMap[K, Either[Serialized, Any]]]] =
    Future.failed(new NotActiveException())

  override def preDriverStarts: Future[Unit] = {
    val cachePromise = Promise[RwLock[TrieMap[K, Either[Serialized, Any]]]]()
    val idPromise = Promise[RwLock[TrieMap[Category, Seq[K]]]]()
    idCache = idPromise.future
    cache = cachePromise.future

    val ids = TrieMap[Category, Seq[K]]()
    val cached = TrieMap[K, Either[Serialized, Any]]()

    val future = store.keys().mapAsync(maxPreloadRequests) { key =>
      store.rawGet(key.key).map(v => key -> v)
    }.runForeach {
      case (categorized, value) =>
        value.foreach(v => cached(categorized.key) = Left(v))
        val children = ids.getOrElse(categorized.category, Nil)
        ids.put(categorized.category, categorized.key +: children)
    }
    idPromise.completeWith(future.map(_ => RwLock(ids)))
    cachePromise.completeWith(future.map(_ => RwLock(cached)))
    future.map(_ => ())
  }

  override def postDriverTerminates: Future[Unit] = {
    cache = Future.failed(new NotActiveException())
    idCache = Future.failed(new NotActiveException())
    Future.successful(())
  }

  override def ids[Id, V]()(implicit ir: Resolver[Id, V]): Source[Id, NotUsed] = {
    val future = async {
      val cached = await(idCache)
      cached.read { c =>
        c.getOrElse(ir.category, Nil).map(ir.fromStorageId)
      }
    }
    Source.fromFuture(future).mapConcat(identity)
  }

  override def deleteAll[Id, V](k: Id)(implicit ir: Resolver[Id, V]): Future[Done] = {
    async {
      val (cached, ids, _) = (await(cache), await(idCache), await(store.deleteAll(k)))
      val storageId = ir.toStorageId(k, None)

      cached.write { c =>
        c.remove(storageId)

        ids.write { i =>
          val old = i.getOrElse(ir.category, Nil)
          val children = old.filter(_ != storageId)
          if (children.nonEmpty) {
            i.put(ir.category, old.filter(_ != storageId))
          } else {
            i.remove(ir.category)
          }
        }
      }
      Done
    }
  }

  override def get[Id, V](id: Id)(implicit ir: Resolver[Id, V], um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    async {
      val cached = await(cache)
      val storageId = ir.toStorageId(id, None)
      cached.read(_.get(storageId)) match {
        case Some(Left(v)) =>
          val deserialized = await(Unmarshal(v).to[V])
          cached.write(_.put(storageId, Right(deserialized)))
          Some(deserialized)
        case Some(Right(v)) =>
          Some(v.asInstanceOf[V])
        case None =>
          None
      }
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: Resolver[Id, V],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    store.get(id, version)

  override def store[Id, V](id: Id, v: V)(implicit ir: Resolver[Id, V], m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] = {
    async {
      val (cached, ids, _) = (await(cache), await(idCache), await(store.store(id, v)))
      cached.write { c =>
        ids.write { i =>
          val storageId = ir.toStorageId(id, None)
          c(storageId) = Right(v)
          val old = i.getOrElse(ir.category, Nil)
          i(ir.category) = storageId +: old
          Done
        }
      }
    }
  }

  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit
    ir: Resolver[Id, V],
    m: Marshaller[V, Serialized]): Future[Done] = async {
    val (valueCache, idCache, _) = (await(this.cache), await(this.idCache), await(store.store(id, v, version)))
    valueCache.write { vc =>
      vc.putIfAbsent(ir.toStorageId(id, None), Right(v))
      idCache.write { idc =>
        val old = idc.getOrElse(ir.category, Nil)
        idc.put(ir.category, ir.toStorageId(id, None) +: old)
      }
    }
    Done
  }

  override def versions[Id, V](id: Id)(implicit ir: Resolver[Id, V]): Source[OffsetDateTime, NotUsed] =
    store.versions(id)

  override def delete[Id, V](k: Id, version: OffsetDateTime)(implicit ir: Resolver[Id, V]): Future[Done] =
    store.delete(k, version)

  override protected[storage] def keys(): Source[CategorizedKey[Category, K], NotUsed] = {
    logger.warn(s"keys() called on a CachingPersistenceStore (which is what keys() is for), deferring to $store")
    store.keys()
  }

  override def toString: String = s"LoadTimeCachingPersistenceStore($store)"
}
