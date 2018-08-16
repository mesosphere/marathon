package mesosphere.marathon
package core.storage.store.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.metrics.{Metrics, Timer}
import mesosphere.marathon.metrics.deprecated.ServiceMetric
import mesosphere.marathon.util.KeyedLock

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

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
abstract class BasePersistenceStore[K, Category, Serialized](
    metrics: Metrics)(implicit
    ctx: ExecutionContext,
    mat: Materializer) extends PersistenceStore[K, Category, Serialized] with StrictLogging {
  private val oldIdsTimeMetric: Timer = metrics.deprecatedTimer(ServiceMetric, getClass, "ids")
  private val newIdsTimeMetric: Timer = metrics.timer("debug.persistence.operations.ids.duration")
  private val oldGetTimeMetric: Timer = metrics.deprecatedTimer(ServiceMetric, getClass, "get")
  private val newGetTimeMetric: Timer = metrics.timer("debug.persistence.operations.get.duration")
  private val oldDeleteTimeMetric: Timer = metrics.deprecatedTimer(ServiceMetric, getClass, "delete")
  private val newDeleteTimeMetric: Timer =
    metrics.timer("debug.persistence.operations.delete.duration")
  private val oldStoreTimeMetric: Timer = metrics.deprecatedTimer(ServiceMetric, getClass, "store")
  private val newStoreTimeMetric: Timer =
    metrics.timer("debug.persistence.operations.store.duration")
  private val oldVersionTimeMetric: Timer = metrics.deprecatedTimer(ServiceMetric, getClass, "versions")
  private val newVersionTimeMetric: Timer =
    metrics.timer("debug.persistence.operations.versions.duration")

  private[this] lazy val lock = KeyedLock[String]("persistenceStore", Int.MaxValue)

  protected def rawIds(id: Category): Source[K, NotUsed]

  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = oldIdsTimeMetric.forSource {
    newIdsTimeMetric.forSource {
      rawIds(ir.category).map(ir.fromStorageId)
    }
  }

  protected def rawVersions(id: K): Source[OffsetDateTime, NotUsed]

  final override def versions[Id, V](
    id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] = oldVersionTimeMetric.forSource {
    newVersionTimeMetric.forSource {
      rawVersions(ir.toStorageId(id, None))
    }
  }

  protected def rawDelete(k: K, version: OffsetDateTime): Future[Done]

  override def deleteVersion[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = oldDeleteTimeMetric {
    newDeleteTimeMetric {
      lock(k.toString) {
        rawDelete(ir.toStorageId(k, Some(version)), version)
      }
    }
  }

  protected def rawDeleteAll(k: K): Future[Done]

  final override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] =
    oldDeleteTimeMetric {
      newDeleteTimeMetric {
        lock(k.toString) {
          rawDeleteAll(ir.toStorageId(k, None))
        }
      }
    }

  protected def rawDeleteCurrent(k: K): Future[Done]

  override def deleteCurrent[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = oldDeleteTimeMetric {
    newDeleteTimeMetric {
      lock(k.toString) {
        rawDeleteCurrent(ir.toStorageId(k, None))
      }
    }
  }

  protected[store] def rawGet(k: K): Future[Option[Serialized]]

  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = oldGetTimeMetric {
    newGetTimeMetric {
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
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = oldGetTimeMetric {
    newGetTimeMetric {
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

  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = oldStoreTimeMetric {
    newStoreTimeMetric {
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
  }

  override def store[Id, V](id: Id, v: V,
    version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = oldStoreTimeMetric {
    newStoreTimeMetric {
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
  }

  /**
    * @return A source of _all_ keys in the Persistence Store (which can be used by a
    *         [[mesosphere.marathon.core.storage.store.impl.cache.LoadTimeCachingPersistenceStore]] to populate the
    *         cache completely on startup.
    */
  protected[store] def allKeys(): Source[CategorizedKey[Category, K], NotUsed]
}
