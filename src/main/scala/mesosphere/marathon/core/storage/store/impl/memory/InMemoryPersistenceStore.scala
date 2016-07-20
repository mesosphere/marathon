package mesosphere.marathon.core.storage.store.impl.memory

import java.time.OffsetDateTime

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.store.impl.{ BasePersistenceStore, CategorizedKey }
import mesosphere.marathon.metrics.Metrics

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }

class InMemoryPersistenceStore(implicit
  protected val mat: Materializer,
  protected val metrics: Metrics,
  ctx: ExecutionContext)
    extends BasePersistenceStore[RamId, String, Identity] {
  val entries = TrieMap[RamId, Identity]()

  override protected def rawIds(category: String): Source[RamId, NotUsed] =
    Source(entries.keySet.filter(id => id.category == category && id.version.isEmpty).toVector)

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
