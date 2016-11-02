package mesosphere.marathon
package core.storage.store.impl.cache

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink => ScalaSink, Source }
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.backup.BackupItem
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.storage.VersionCacheConfig
import mesosphere.marathon.stream.Sink
import mesosphere.marathon.util.KeyedLock

import scala.async.Async.{ async, await }
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Set
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

  private val lock = KeyedLock[String]("LazyCachingStore", Int.MaxValue)
  private[store] val idCache = TrieMap.empty[Category, Set[Any]]
  private[store] val valueCache = TrieMap.empty[K, Option[Any]]

  override def storageVersion(): Future[Option[StorageVersion]] = store.storageVersion()

  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] =
    store.setStorageVersion(storageVersion)

  @SuppressWarnings(Array("all")) // async/await
  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = {
    val category = ir.category
    val idsFuture = lock(category.toString) {
      if (idCache.contains(category)) {
        Future.successful(idCache(category).asInstanceOf[Set[Id]])
      } else {
        async {
          val children = await(store.ids.runWith(Sink.set[Any])) // linter:ignore UndesirableTypeInference
          idCache(category) = children
          children
        }
      }
    }
    Source.fromFuture(idsFuture).mapConcat(_.asInstanceOf[Set[Id]])
  }

  @SuppressWarnings(Array("all")) // async/await
  private def deleteCurrentOrAll[Id, V](
    k: Id,
    delete: () => Future[Done])(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    val category = ir.category
    val storageId = ir.toStorageId(k, None)
    lock(category.toString) {
      lock(storageId.toString) {
        async { // linter:ignore UnnecessaryElseBranch
          await(delete())
          valueCache.remove(storageId)
          val old = idCache.getOrElse(category, Set.empty[Any]) // linter:ignore UndesirableTypeInference
          val children = old - k.asInstanceOf[Any] // linter:ignore UndesirableTypeInference
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
    lock(storageId.toString) {
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
        async { // linter:ignore UnnecessaryElseBranch
          await(store.store(id, v))
          valueCache.put(storageId, Some(v))
          val cachedIds = idCache.getOrElse(category, Set.empty[Any]) // linter:ignore UndesirableTypeInference
          idCache.put(category, cachedIds + id.asInstanceOf[Any])
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
        await(store.store(id, v, version))
        val old = idCache.getOrElse(category, Set.empty[Any]) // linter:ignore UndesirableTypeInference
        idCache.put(category, old + id)
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

  override def backup(): Source[BackupItem, NotUsed] = store.backup()

  override def restore(): ScalaSink[BackupItem, Future[Done]] = store.restore()

  override def toString: String = s"LazyCachingPersistenceStore($store)"
}

case class LazyVersionCachingPersistentStore[K, Category, Serialized](
    store: PersistenceStore[K, Category, Serialized],
    config: VersionCacheConfig = VersionCacheConfig.Default)(implicit
  mat: Materializer,
    ctx: ExecutionContext) extends PersistenceStore[K, Category, Serialized] with StrictLogging {

  private[store] val versionCache = TrieMap.empty[(Category, K), Set[OffsetDateTime]]
  private[store] val versionedValueCache = TrieMap.empty[(K, OffsetDateTime), Option[Any]]

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

  private[this] def updateCachedVersions[Id, V](
    id: Id,
    version: OffsetDateTime,
    v: Option[V])(implicit ir: IdResolver[Id, V, Category, K]): Unit = {

    val category = ir.category
    val unversionedId = ir.toStorageId(id, None)
    maybePurgeCachedVersions()
    versionedValueCache.put((unversionedId, version), v)
    val cached = versionCache.getOrElse((category, unversionedId), Set.empty) // linter:ignore UndesirableTypeInference
    versionCache.put((category, unversionedId), cached + version)
  }

  @SuppressWarnings(Array("all")) // async/await
  private def deleteCurrentOrAll[Id, V](
    id: Id, delete: () => Future[Done])(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {

    if (!ir.hasVersions) {
      delete()
    } else {
      val category = ir.category
      val storageId = ir.toStorageId(id, None)

      async {
        await(delete())
        versionedValueCache.retain { case ((sid, version), v) => sid != storageId }
        versionCache.remove((category, storageId))
        Done
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
      async {
        val value = await(store.get(id))
        value.foreach { v =>
          val version = ir.version(v)
          updateCachedVersions(id, version, value)
        }
        value
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = {

    val storageId = ir.toStorageId(id, None)
    val cached = versionedValueCache.get((storageId, version)) // linter:ignore OptionOfOption
    cached match {
      case Some(v: Option[V] @unchecked) =>
        Future.successful(v)
      case _ =>
        async {
          val value = await(store.get(id, version))
          updateCachedVersions(id, version, value)
          value
        }
    }
  }

  /**
    * TODO: no caching here yet, intended only for migration (for now)
    */
  override def getVersions[Id, V](list: Seq[(Id, OffsetDateTime)])(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Source[V, NotUsed] =
    store.getVersions(list)

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {

    if (!ir.hasVersions) {
      store.store(id, v)
    } else {
      async {
        await(store.store(id, v))
        val version = ir.version(v)
        updateCachedVersions(id, version, Some(v))
        Done
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {

    async {
      await(store.store(id, v, version))
      updateCachedVersions(id, version, Some(v))
      Done
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
    val versionsFuture = {
      val category = ir.category
      val storageId = ir.toStorageId(id, None)
      if (versionCache.contains((category, storageId))) {
        Future.successful(versionCache((category, storageId)))
      } else {
        async {
          val children = await(store.versions(id).runWith(Sink.set))
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

  override def backup(): Source[BackupItem, NotUsed] = store.backup()

  override def restore(): ScalaSink[BackupItem, Future[Done]] = store.restore()

  override def toString: String = s"LazyVersionCachingPersistenceStore($store)"
}
