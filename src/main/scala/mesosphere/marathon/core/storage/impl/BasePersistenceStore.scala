package mesosphere.marathon.core.storage.impl

import java.time.{Clock, OffsetDateTime}

import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.{Done, NotUsed}
import mesosphere.marathon.core.storage.{IdResolver, PersistenceStore, VersionedId}
import mesosphere.util.LockManager

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}

/**
  * Persistence Store that handles all marshalling and unmarshalling, allowing
  * subclasses to focus on the raw formatted data.
  *
  * @param maxVersions The maximum number of versions allowed.
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
abstract class BasePersistenceStore[K, Serialized](maxVersions: Int,
                                              clock: Clock = Clock.systemUTC())(
                                                  implicit ctx: ExecutionContext,
                                                  mat: Materializer
) extends PersistenceStore[K, Serialized]
  with TimedPersistenceStore[K, Serialized] {

  private[this] lazy val lockManager = LockManager.create()

  protected def rawVersions(id: K): Source[VersionedId[K], NotUsed]

  final override def versions[Id, V](id: Id)(implicit
                                       ir: IdResolver[Id, K, V, Serialized]): Source[VersionedId[Id], NotUsed] = {
    rawVersions(ir.toStorageId(id, None)).map { versionedId =>
      VersionedId(ir.fromStorageId(versionedId.id), versionedId.version)
    }
  }

  protected def rawDelete(k: K, version: OffsetDateTime): Future[Done]

  override def delete[Id, V](k: Id,
                             version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] = {
    val storageId = ir.toStorageId(k, Some(version))
    lockManager.executeSequentially(storageId.toString) {
      rawDelete(ir.toStorageId(k, Some(version)), version)
    }
  }

  protected def rawDeleteAll(k: K): Future[Done]

  final override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, K, V, Serialized]): Future[Done] = {
    val storageId = ir.toStorageId(k, None)
    lockManager.executeSequentially(storageId.toString) {
      rawDeleteAll(ir.toStorageId(k, None))
    }
  }

  protected def rawGet(k: K): Future[Option[Serialized]]

  override def get[Id, V](id: Id)(implicit ir: IdResolver[Id, K, V, Serialized],
                                  um: Unmarshaller[Serialized, V]): Future[Option[V]] = async {
    val storageId = ir.toStorageId(id, None)
    await(rawGet(storageId)) match {
      case Some(v) =>
        Some(await(Unmarshal(v).to[V]))
      case None =>
        None
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized],
                                                           um: Unmarshaller[Serialized, V]): Future[Option[V]] = async {
    val storageId = ir.toStorageId(id, Some(version))
    await(rawGet(storageId)) match {
      case Some(v) =>
        Some(await(Unmarshal(v).to[V]))
      case None =>
        None
    }
  }

  protected def deleteOld(k: K): Future[Done] = async {
    val versions = await(rawVersions(k).toMat(Sink.seq)(Keep.right).run()).sortBy(_.version.toEpochSecond)
    val numToDelete = maxVersions - versions.size
    val deletes = versions.take(numToDelete).map(v => rawDelete(v.id, v.version))
    await(Future.sequence(deletes))
    Done
  }

  protected def rawStore[V](k: K, v: Serialized): Future[Done]

  private def storeInternal[Id, V](id: Id, v: V, version: Option[OffsetDateTime])
                          (implicit ir: IdResolver[Id, K, V, Serialized],
                           m: Marshaller[V, Serialized]): Future[Done] = {
    val storageId = ir.toStorageId(id, version)
    lockManager.executeSequentially(storageId.toString) {
      async {
        val serialized = await(Marshal(v).to[Serialized])
        await(rawStore(storageId, serialized))
        await(deleteOld(storageId))
        Done
      }
    }
  }

  override def store[Id, V](id: Id, v: V)(implicit ir: IdResolver[Id, K, V, Serialized],
                                          m: Marshaller[V, Serialized]): Future[Done] = {
    storeInternal(id, v, None)
  }


  override def store[Id, V](id: Id, v: V,
                            version: OffsetDateTime)(implicit ir: IdResolver[Id, K, V, Serialized],
                                                     m: Marshaller[V, Serialized]): Future[Done] = {
    storeInternal(id, v, Some(version))
  }
}
