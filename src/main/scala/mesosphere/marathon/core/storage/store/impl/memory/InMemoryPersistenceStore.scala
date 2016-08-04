package mesosphere.marathon.core.storage.store.impl.memory

import java.time.OffsetDateTime

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.migration.StorageVersions
import mesosphere.marathon.core.storage.store.impl.{ BasePersistenceStore, CategorizedKey }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.util.Lock

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }

class InMemoryPersistenceStore(implicit
  protected val mat: Materializer,
  protected val metrics: Metrics,
  ctx: ExecutionContext)
    extends BasePersistenceStore[RamId, String, Identity] {
  val entries = TrieMap[RamId, Identity]()
  val version = Lock(StorageVersions.current.toBuilder)

  override private[storage] def storageVersion(): Future[Option[StorageVersion]] = {
    Future.successful(Some(version(_.build())))
  }

  override private[storage] def setStorageVersion(storageVersion: StorageVersion): Future[Done] = {
    version(_.mergeFrom(storageVersion))
    Future.successful(Done)
  }

  override protected def rawIds(category: String): Source[RamId, NotUsed] = {
    val ids = entries.keySet.filter(_.category == category)
    // we need to list the id even if there is no current version.
    Source(ids.groupBy(_.id).map(_._2.head))
  }

  override protected[store] def rawGet(k: RamId): Future[Option[Identity]] =
    Future.successful(entries.get(k))

  override protected def rawDelete(k: RamId, version: OffsetDateTime): Future[Done] = {
    entries.remove(k.copy(version = Some(version)))
    Future.successful(Done)
  }

  override protected def rawStore[V](k: RamId, v: Identity): Future[Done] = {
    entries.put(k, v)
    Future.successful(Done)
  }

  override protected def rawVersions(id: RamId): Source[OffsetDateTime, NotUsed] = {
    val versions = entries.withFilter {
      case (k, _) => k.category == id.category && k.id == id.id && k.version.isDefined
    }.map { case (k, _) => k.version.get }
    Source(versions.toVector)
  }

  override protected def rawDeleteCurrent(k: RamId): Future[Done] = {
    entries.remove(k)
    Future.successful(Done)
  }

  override protected def rawDeleteAll(k: RamId): Future[Done] = {
    val toRemove = entries.keySet.filter(id => k.category == id.category && k.id == id.id)
    toRemove.foreach(entries.remove)
    Future.successful(Done)
  }

  override protected[store] def allKeys(): Source[CategorizedKey[String, RamId], NotUsed] =
    Source(entries.keySet.filter(_.version.isEmpty).map(id => CategorizedKey(id.category, id))(collection.breakOut))
}
