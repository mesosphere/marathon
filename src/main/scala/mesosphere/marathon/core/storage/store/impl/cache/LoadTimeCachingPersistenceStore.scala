package mesosphere.marathon
package core.storage.store.impl.cache

import java.io.NotActiveException
import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.backup.BackupItem
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.util.KeyedLock

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
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
    val store: BasePersistenceStore[K, Category, Serialized],
    maxPreloadRequests: Int = 8)(
    implicit
    mat: Materializer,
    ctx: ExecutionContext
) extends PersistenceStore[K, Category, Serialized] with StrictLogging with PrePostDriverCallback {

  private val lock = KeyedLock[String]("LoadTimeCachingStore", Int.MaxValue)
  private[store] var idCache: Future[TrieMap[Category, Set[K]]] = Future.failed(new NotActiveException())
  // When we pre-load the persistence store, we don't have an idResolver or an Unmarshaller, so we store the
  // serialized form as a Left() until it is deserialized, in which case we store as a Right()
  private[store] var valueCache: Future[TrieMap[K, Either[Serialized, Any]]] =
    Future.failed(new NotActiveException())

  override def storageVersion(): Future[Option[StorageVersion]] = store.storageVersion()

  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] =
    store.setStorageVersion(storageVersion)

  override def preDriverStarts: Future[Unit] = {
    val cachePromise = Promise[TrieMap[K, Either[Serialized, Any]]]()
    val idPromise = Promise[TrieMap[Category, Set[K]]]()
    idCache = idPromise.future
    valueCache = cachePromise.future

    val ids = TrieMap.empty[Category, Set[K]]
    val cached = TrieMap.empty[K, Either[Serialized, Any]]

    val future = store.allKeys().mapAsync(maxPreloadRequests) { key =>
      store.rawGet(key.key).map(v => key -> v)
    }.runForeach {
      case (categorized, value) =>
        value.foreach(v => cached(categorized.key) = Left(v))
        val children = ids.getOrElse(categorized.category, Set.empty)
        ids.put(categorized.category, children + categorized.key)
    }
    idPromise.completeWith(future.map(_ => ids))
    cachePromise.completeWith(future.map(_ => cached))
    future.map(_ => ())
  }

  override def postDriverTerminates: Future[Unit] = {
    valueCache = Future.failed(new NotActiveException())
    idCache = Future.failed(new NotActiveException())
    Future.successful(())
  }

  @SuppressWarnings(Array("all")) // async/await
  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = {
    val category = ir.category
    val future = lock(category.toString) {
      async {
        await(idCache).getOrElse(category, Nil).map(ir.fromStorageId)
      }
    }
    Source.fromFuture(future).mapConcat(identity)
  }

  @SuppressWarnings(Array("all")) // async/await
  private def deleteCurrentOrAll[Id, V](
    k: Id,
    delete: () => Future[Done])(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    val storageId = ir.toStorageId(k, None)
    val category = ir.category
    lock(category.toString) {
      lock(storageId.toString) {
        async {
          val deleteFuture = delete()
          val (cached, ids, _) = (await(valueCache), await(idCache), await(deleteFuture))
          cached.remove(storageId)
          val old = ids.getOrElse(category, Set.empty)
          val children = old - storageId
          if (children.nonEmpty) { // linter:ignore:UseIfExpression
            ids.put(category, children)
          } else {
            ids.remove(category)
          }
          Done
        }
      }
    }
  }

  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    deleteCurrentOrAll(k, () => store.deleteAll(k))
  }

  override def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    deleteCurrentOrAll(k, () => store.deleteCurrent(k))
  }

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    val storageId = ir.toStorageId(id, None)
    lock(storageId.toString) {
      async {
        val cached = await(valueCache)
        cached.get(storageId) match {
          case Some(Left(v)) =>
            val deserialized = await(Unmarshal(v).to[V])
            cached.put(storageId, Right(deserialized))
            Some(deserialized)
          case Some(Right(v)) =>
            Some(v.asInstanceOf[V])
          case None =>
            None
        }
      }
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    store.get(id, version)

  override def getVersions[Id, V](list: Seq[(Id, OffsetDateTime)])(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Source[V, NotUsed] =
    store.getVersions(list)

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(id, None)
    lock(category.toString) {
      lock(storageId.toString) {
        async {
          val storeFuture = store.store(id, v)
          val (cached, ids, _) = (await(valueCache), await(idCache), await(storeFuture))
          cached(storageId) = Right(v)
          val old = ids.getOrElse(ir.category, Set.empty)
          ids(category) = old + storageId
          Done
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(id, None)
    lock(category.toString) {
      async {
        val storeFuture = store.store(id, v, version)
        val (idCache, _) = (await(this.idCache), await(storeFuture))
        val old = idCache.getOrElse(category, Set.empty)
        idCache.put(category, old + storageId)
        Done
      }
    }
  }

  override def backup(): Source[BackupItem, NotUsed] = store.backup()

  override def restore(): Sink[BackupItem, Future[Done]] = store.restore()

  override def versions[Id, V](id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] =
    store.versions(id)

  override def deleteVersion[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    store.deleteVersion(k, version)

  override def toString: String = s"LoadTimeCachingPersistenceStore($store)"
}
