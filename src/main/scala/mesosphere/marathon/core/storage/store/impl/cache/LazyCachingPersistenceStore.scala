package mesosphere.marathon.core.storage.store.impl.cache

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.storage.VersionCacheConfig
import mesosphere.util.LockManager

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

/**
  * A Write Ahead Cache of another persistence store that lazily loads values into the cache.
  *
  * @param store The store to cache
  * @param mat a materializer for Akka Streaming
  * @param ctx The execution context for future chaining.
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
case class LazyCachingPersistenceStore[K, Category, Serialized](
    store: BasePersistenceStore[K, Category, Serialized])(implicit
  mat: Materializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Category, Serialized] with StrictLogging {

  private val lockManager = LockManager.create()
  private[store] val idCache = TrieMap.empty[Category, Seq[Any]]
  private[store] val valueCache = TrieMap.empty[K, Option[Any]]

  override def storageVersion(): Future[Option[StorageVersion]] = store.storageVersion()

  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] =
    store.setStorageVersion(storageVersion)

  @SuppressWarnings(Array("all")) // async/await
  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = {
    val category = ir.category
    val idsFuture = lockManager.executeSequentially(category.toString) {
      if (idCache.contains(category)) {
        Future.successful(idCache(category).asInstanceOf[Seq[Id]])
      } else {
        async { // linter:ignore UnnecessaryElseBranch
          val children = await(store.ids.toMat(Sink.seq)(Keep.right).run())
          idCache(category) = children
          children
        }
      }
    }
    Source.fromFuture(idsFuture).mapConcat(identity)
  }

  @SuppressWarnings(Array("all")) // async/await
  private def deleteCurrentOrAll[Id, V](
    k: Id,
    delete: () => Future[Done])(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(k, None)
    lockManager.executeSequentially(category.toString) {
      lockManager.executeSequentially(storageId.toString) {
        async { // linter:ignore UnnecessaryElseBranch
          await(delete())
          valueCache.remove(storageId)
          val old = idCache.getOrElse(category, Nil) // linter:ignore UndesirableTypeInference
          val children = old.filter(_ != k) // linter:ignore UndesirableTypeInference
          if (children.nonEmpty) { // linter:ignore UnnecessaryElseBranch+UseIfExpression
            idCache.put(category, children)
          } else {
            idCache.remove(category)
          }
          Done
        }
      }
    }
  }

  override def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    deleteCurrentOrAll(k, () => store.deleteCurrent(k))
  }

  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    deleteCurrentOrAll(k, () => store.deleteAll(k))
  }

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially(storageId.toString) {
      val cached = valueCache.get(storageId) // linter:ignore OptionOfOption
      cached match {
        case Some(v: Option[V] @unchecked) =>
          Future.successful(v)
        case _ =>
          async { // linter:ignore UnnecessaryElseBranch
            val value = await(store.get(id))
            valueCache.put(storageId, value)
            value
          }
      }
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] =
    store.get(id, version)

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially(category.toString) {
      lockManager.executeSequentially(storageId.toString) {
        async { // linter:ignore UnnecessaryElseBranch
          await(store.store(id, v))
          valueCache.put(storageId, Some(v))
          val cachedIds = idCache.getOrElse(category, Nil) // linter:ignore UndesirableTypeInference
          idCache.put(category, id +: cachedIds)
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
    lockManager.executeSequentially(category.toString) {
      async { // linter:ignore UnnecessaryElseBranch
        await(store.store(id, v, version))
        val old = idCache.getOrElse(category, Nil) // linter:ignore UndesirableTypeInference
        idCache.put(category, id +: old)
        Done
      }
    }
  }

  override def versions[Id, V](id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] =
    store.versions(id)

  override def deleteVersion[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    store.deleteVersion(k, version)

  override def toString: String = s"LazyCachingPersistenceStore($store)"
}

case class LazyVersionCachingPersistentStore[K, Category, Serialized](
    store: PersistenceStore[K, Category, Serialized],
    config: VersionCacheConfig = VersionCacheConfig.Default)(implicit
  mat: Materializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Category, Serialized] with StrictLogging {

  private val lockManager = LockManager.create()
  private[store] val versionCache = TrieMap.empty[(Category, K), Seq[OffsetDateTime]]
  private[store] val versionedValueCache = TrieMap.empty[(K, OffsetDateTime), Option[Any]]

  def withVersionedValueCache[Id, V, T](
    future: => Future[T])(implicit ir: IdResolver[Id, V, Category, K]): Future[T] =
    lockManager.executeSequentially(s"versionedValueCache::${ir.category}")(future)

  def withVersionCache[Id, V, T](
    id: Id)(future: (Category, K) => Future[T])(implicit ir: IdResolver[Id, V, Category, K]): Future[T] = {
    val category = ir.category
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially((category, storageId).toString())(future(category, storageId))
  }

  private[cache] def maybePurgeCachedVersions(
    maxEntries: Int = config.maxEntries,
    purgeCount: Int = config.purgeCount,
    pRemove: Double = config.pRemove): Unit =

    while (versionedValueCache.size > maxEntries) {
      // randomly GC the versions
      var counter = 0
      versionedValueCache.retain { (k, v) =>
        val x = Random.nextDouble()
        x > pRemove || { counter += 1; counter > purgeCount }
      }
    }

  /**
    * Assumes that callers have already implemented appropriate locking via [[withVersionCache]] and
    * [[withVersionedValueCache]].
    */
  private[this] def updateCachedVersions[V](
    storageId: K,
    version: OffsetDateTime,
    category: Category,
    v: Option[V]): Unit = {

    maybePurgeCachedVersions()
    versionedValueCache.put((storageId, version), v)
    val cached = versionCache.getOrElse((category, storageId), Nil) // linter:ignore UndesirableTypeInference
    versionCache.put((category, storageId), (version +: cached).distinct)
  }

  @SuppressWarnings(Array("all")) // async/await
  private def deleteCurrentOrAll[Id, V](
    id: Id, delete: () => Future[Done])(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {

    if (!ir.hasVersions) {
      delete()
    } else {
      withVersionCache(id) { (category, storageId) =>
        withVersionedValueCache {
          async {
            // linter:ignore UnnecessaryElseBranch
            await(delete())
            versionedValueCache.retain { case ((sid, version), v) => sid != storageId }
            versionCache.remove((category, storageId))
            Done
          }
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {

    if (!ir.hasVersions) {
      store.get(id)
    } else {
      withVersionCache(id) { (category, storageId) =>
        withVersionedValueCache {
          async { // linter:ignore UnnecessaryElseBranch
            val value = await(store.get(id))
            value.foreach { v =>
              val version = ir.version(v)
              updateCachedVersions(storageId, version, category, value)
            }
            value
          }
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {

    withVersionCache(id) { (category, storageId) =>
      withVersionedValueCache {
        val cached = versionedValueCache.get((storageId, version)) // linter:ignore OptionOfOption
        cached match {
          case Some(v: Option[V] @unchecked) =>
            Future.successful(v)
          case _ =>
            async { // linter:ignore UnnecessaryElseBranch
              val value = await(store.get(id, version))
              updateCachedVersions(storageId, version, category, value)
              value
            }
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {

    if (!ir.hasVersions) {
      store.store(id, v)
    } else {
      withVersionCache(id) { (category, storageId) =>
        withVersionedValueCache {
          async { // linter:ignore UnnecessaryElseBranch
            await(store.store(id, v))
            val version = ir.version(v)
            updateCachedVersions(storageId, version, category, Some(v))
            Done
          }
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {

    withVersionCache(id) { (category, storageId) =>
      withVersionedValueCache {
        async { // linter:ignore UnnecessaryElseBranch
          await(store.store(id, v, version))
          updateCachedVersions(storageId, version, category, Some(v))
          Done
        }
      }
    }
  }

  override def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    deleteCurrentOrAll(k, () => store.deleteCurrent(k))

  override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    deleteCurrentOrAll(k, () => store.deleteAll(k))

  override def deleteVersion[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    deleteCurrentOrAll(k, () => store.deleteVersion(k, version))

  @SuppressWarnings(Array("all")) // async/await
  override def versions[Id, V](id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] = {
    val versionsFuture = withVersionCache(id) { (category, storageId) =>
      if (versionCache.contains((category, storageId))) {
        Future.successful(versionCache((category, storageId)))
      } else {
        async { // linter:ignore UnnecessaryElseBranch
          val children = await(store.versions(id).toMat(Sink.seq)(Keep.right).run())
          versionCache((category, storageId)) = children
          children
        }
      }
    }
    Source.fromFuture(versionsFuture).mapConcat(identity)
  }

  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = store.ids()

  override def storageVersion(): Future[Option[StorageVersion]] = store.storageVersion()

  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] =
    store.setStorageVersion(storageVersion)

  override def toString: String = s"LazyVersionCachingPersistenceStore($store)"
}
