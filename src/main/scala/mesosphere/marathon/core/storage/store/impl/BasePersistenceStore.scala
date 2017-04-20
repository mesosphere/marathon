package mesosphere.marathon
package core.storage.store.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.{ Marshal, Marshaller }
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, Timer }
import mesosphere.marathon.util.KeyedLock

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

case class CategorizedKey[C, K](category: C, key: K)

/**
  * Persistence Store that handles all marshalling and unmarshalling, allowing
  * subclasses to focus on the raw formatted data.
  *
  * Note: when an object _is_ versioned (maxVersions >= 1), store will store the object _twice_,
  * once with its unversioned form and once with its versioned form.
  * This prevents the need to:
  * - Find the current object when updating it.
  * - Find the current object to list it in versions.
  * - Unmarshal the current object.
  *
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
abstract class BasePersistenceStore[K, Category, Serialized](implicit
  ctx: ExecutionContext,
    mat: Materializer) extends PersistenceStore[K, Category, Serialized] with StrictLogging {
  private val idsTimer: Timer = Metrics.timer(ServiceMetric, getClass, "ids")
  private val getTimer: Timer = Metrics.timer(ServiceMetric, getClass, "get")
  private val deleteTimer: Timer = Metrics.timer(ServiceMetric, getClass, "delete")
  private val storeTimer: Timer = Metrics.timer(ServiceMetric, getClass, "store")
  private val versionTimer: Timer = Metrics.timer(ServiceMetric, getClass, "versions")

  private[this] lazy val lock = KeyedLock[String]("persistenceStore", Int.MaxValue)

  protected def rawIds(id: Category): Source[K, NotUsed]

  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = idsTimer.forSource {
    rawIds(ir.category).map(ir.fromStorageId)
  }

  protected def rawVersions(id: K): Source[OffsetDateTime, NotUsed]

  final override def versions[Id, V](
    id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] = versionTimer.forSource {
    rawVersions(ir.toStorageId(id, None))
  }

  protected def rawDelete(k: K, version: OffsetDateTime): Future[Done]

  override def deleteVersion[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = deleteTimer {
    lock(k.toString) {
      rawDelete(ir.toStorageId(k, Some(version)), version)
    }
  }

  protected def rawDeleteAll(k: K): Future[Done]

  final override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = deleteTimer {
    lock(k.toString) {
      rawDeleteAll(ir.toStorageId(k, None))
    }
  }

  protected def rawDeleteCurrent(k: K): Future[Done]

  override def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = deleteTimer {
    lock(k.toString) {
      rawDeleteCurrent(ir.toStorageId(k, None))
    }
  }

  protected[store] def rawGet(k: K): Future[Option[Serialized]]

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = getTimer {
    async {
      val storageId = ir.toStorageId(id, None)
      await(rawGet(storageId)) match {
        case Some(v) =>
          Some(await(Unmarshal(v).to[V]))
        case None =>
          None
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = getTimer {
    async {
      val storageId = ir.toStorageId(id, Some(version))
      await(rawGet(storageId)) match {
        case Some(v) =>
          Some(await(Unmarshal(v).to[V]))
        case None =>
          None
      }
    }
  }

  override def getVersions[Id, V](list: Seq[(Id, OffsetDateTime)])(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Source[V, NotUsed] = {

    Source(list).mapAsync[Option[Serialized]](Int.MaxValue) {
      case (id, version) =>
        val storageId = ir.toStorageId(id, Some(version))
        rawGet(storageId)
    }.collect {
      case Some(marshaled) => marshaled
    }.mapAsync(Int.MaxValue) { marshaled =>
      Unmarshal(marshaled).to[V]
    }
  }

  protected def rawStore[V](k: K, v: Serialized): Future[Done]

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = storeTimer {
    val unversionedId = ir.toStorageId(id, None)
    lock(id.toString) {
      async {
        val serialized = await(Marshal(v).to[Serialized])
        val storeCurrent = rawStore(unversionedId, serialized)
        val storeVersioned = if (ir.hasVersions) {
          rawStore(ir.toStorageId(id, Some(ir.version(v))), serialized)
        } else {
          Future.successful(Done)
        }
        await(storeCurrent)
        await(storeVersioned)
        Done
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def store[Id, V](id: Id, v: V,
    version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = storeTimer {
    if (ir.hasVersions) {
      val storageId = ir.toStorageId(id, Some(version))
      lock(id.toString) {
        async {
          val serialized = await(Marshal(v).to[Serialized])
          await(rawStore(storageId, serialized))
          Done
        }
      }
    } else {
      logger.warn(s"Attempted to store a versioned value for $id which is not versioned.")
      Future.successful(Done)
    }
  }

  /**
    * @return A source of _all_ keys in the Persistence Store (which can be used by a
    *         [[mesosphere.marathon.core.storage.store.impl.cache.LoadTimeCachingPersistenceStore]] to populate the
    *         cache completely on startup.
    */
  protected[store] def allKeys(): Source[CategorizedKey[Category, K], NotUsed]
}
