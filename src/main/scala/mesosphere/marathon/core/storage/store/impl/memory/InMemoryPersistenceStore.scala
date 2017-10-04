package mesosphere.marathon.core.storage.store.impl.memory

import java.time.OffsetDateTime

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.store.impl.{ BasePersistenceStore, CategorizedKey }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.migration.StorageVersions
import mesosphere.marathon.util.Lock

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }

case class RamId(category: String, id: String, version: Option[OffsetDateTime])

case class Identity(value: Any)

class InMemoryPersistenceStore(implicit
  protected val mat: Materializer,
  protected val metrics: Metrics,
  ctx: ExecutionContext)
    extends BasePersistenceStore[RamId, String, Identity] {

  val entries = TrieMap[RamId, Identity]()
  val version = Lock(StorageVersions.current.toBuilder)

  override def storageVersion(): Future[Option[StorageVersion]] = {
    require(isOpen, "the store must be opened before it can be used")

    Future.successful(Some(version(_.build())))
  }

  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] = {
    require(isOpen, "the store must be opened before it can be used")

    version(_.mergeFrom(storageVersion))
    Future.successful(Done)
  }

  override protected def rawIds(category: String): Source[RamId, NotUsed] = {
    require(isOpen, "the store must be opened before it can be used")

    val ids = entries.keySet.filter(_.category == category)
    // we need to list the id even if there is no current version.
    Source(ids.groupBy(_.id).flatMap(_._2.headOption))
  }

  override protected[store] def rawGet(k: RamId): Future[Option[Identity]] = {
    require(isOpen, "the store must be opened before it can be used")

    Future.successful(entries.get(k))
  }

  override protected def rawDelete(k: RamId, version: OffsetDateTime): Future[Done] = {
    require(isOpen, "the store must be opened before it can be used")

    entries.remove(k.copy(version = Some(version)))
    Future.successful(Done)
  }

  override protected def rawStore[V](k: RamId, v: Identity): Future[Done] = {
    require(isOpen, "the store must be opened before it can be used")

    entries.put(k, v)
    Future.successful(Done)
  }

  override protected def rawVersions(id: RamId): Source[OffsetDateTime, NotUsed] = {
    require(isOpen, "the store must be opened before it can be used")

    val versions = entries.collect {
      case (RamId(category, rid, Some(v)), _) if category == id.category && id.id == rid => v
    }(collection.breakOut)
    Source(versions)
  }

  override protected def rawDeleteCurrent(k: RamId): Future[Done] = {
    require(isOpen, "the store must be opened before it can be used")

    entries.remove(k)
    Future.successful(Done)
  }

  override protected def rawDeleteAll(k: RamId): Future[Done] = {
    require(isOpen, "the store must be opened before it can be used")

    val toRemove = entries.keySet.filter(id => k.category == id.category && k.id == id.id)
    toRemove.foreach(entries.remove)
    Future.successful(Done)
  }

  override protected[store] def allKeys(): Source[CategorizedKey[String, RamId], NotUsed] = {
    require(isOpen, "the store must be opened before it can be used")

    Source(entries.keySet.filter(_.version.isEmpty).map(id => CategorizedKey(id.category, id))(collection.breakOut))
  }

  override def sync(): Future[Done] = {
    require(isOpen, "the store must be opened before it can be used")

    Future.successful(Done)
  }
}
