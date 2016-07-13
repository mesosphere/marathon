package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import akka.http.scaladsl.marshalling.{ Marshal, Marshaller }
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }
import mesosphere.util.LockManager

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Persistence Store that handles all marshalling and unmarshalling, allowing
  * subclasses to focus on the raw formatted data.
  *
  * @tparam K The persistence store's primary key type
  * @tparam Serialized The serialized format for the persistence store.
  */
abstract class BasePersistenceStore[K, Category, Serialized](implicit
  ctx: ExecutionContext,
  mat: Materializer) extends PersistenceStore[K, Category, Serialized]
    with TimedPersistenceStore[K, Category, Serialized] {

  private[this] lazy val lockManager = LockManager.create()

  protected def rawIds(id: Category): Source[K, NotUsed]

  override def ids[Id, V]()(implicit ir: IdResolver[Id, V, Category, K]): Source[Id, NotUsed] = {
    rawIds(ir.category).map(ir.fromStorageId)
  }

  protected def rawVersions(id: K): Source[OffsetDateTime, NotUsed]

  final override def versions[Id, V](
    id: Id)(implicit ir: IdResolver[Id, V, Category, K]): Source[OffsetDateTime, NotUsed] = {
    rawVersions(ir.toStorageId(id, None))
  }

  protected def rawDelete(k: K, version: OffsetDateTime): Future[Done]

  override def delete[Id, V](
    k: Id,
    version: OffsetDateTime)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    val storageId = ir.toStorageId(k, Some(version))
    lockManager.executeSequentially(k.toString) {
      rawDelete(ir.toStorageId(k, Some(version)), version)
    }
  }

  protected def rawDeleteAll(k: K): Future[Done]

  final override def deleteAll[Id, V](k: Id)(implicit ir: IdResolver[Id, V, Category, K]): Future[Done] = {
    val storageId = ir.toStorageId(k, None)
    lockManager.executeSequentially(k.toString) {
      rawDeleteAll(ir.toStorageId(k, None))
    }
  }

  protected[storage] def rawGet(k: K): Future[Option[Serialized]]

  override def get[Id, V](id: Id)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = async {
    val storageId = ir.toStorageId(id, None)
    await(rawGet(storageId)) match {
      case Some(v) =>
        Some(await(Unmarshal(v).to[V]))
      case None =>
        None
    }
  }

  override def get[Id, V](id: Id, version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    um: Unmarshaller[Serialized, V]): Future[Option[V]] = async {
    val storageId = ir.toStorageId(id, Some(version))
    await(rawGet(storageId)) match {
      case Some(v) =>
        Some(await(Unmarshal(v).to[V]))
      case None =>
        None
    }
  }

  protected def deleteOld(k: K, maxVersions: Int): Future[Done] = async {
    val versions = await(rawVersions(k).toMat(Sink.seq)(Keep.right).run()).sortBy(_.toEpochSecond)
    val numToDelete = versions.size - maxVersions
    if (numToDelete > 0) {
      val deletes = versions.take(numToDelete).map(v => rawDelete(k, v))
      await(Future.sequence(deletes))
    }
    Done
  }

  protected def rawStore[V](k: K, v: Serialized): Future[Done]

  override def store[Id, V](id: Id, v: V)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized],
    um: Unmarshaller[Serialized, V]): Future[Done] = {
    val storageId = ir.toStorageId(id, None)
    lockManager.executeSequentially(id.toString) {
      async {
        val serialized = await(Marshal(v).to[Serialized])
        await(rawGet(storageId)) match {
          case Some(oldValue) =>
            val unmarshalled = await(Unmarshal(oldValue).to[V])
            val versionedId = ir.toStorageId(id, Some(ir.version(unmarshalled)))
            await(rawStore(versionedId, oldValue))
          case None =>
        }

        await(rawStore(storageId, serialized))
        await(deleteOld(storageId, ir.maxVersions))
        Done
      }
    }
  }

  override def store[Id, V](id: Id, v: V,
    version: OffsetDateTime)(implicit
    ir: IdResolver[Id, V, Category, K],
    m: Marshaller[V, Serialized]): Future[Done] = {

    val storageId = ir.toStorageId(id, Some(version))
    val currentId = ir.toStorageId(id, None)
    lockManager.executeSequentially(id.toString) {
      async {
        val serialized = await(Marshal(v).to[Serialized])
        await(rawGet(currentId)) match {
          case Some(currentValue) =>
          case None =>
            await(rawStore(currentId, serialized))
        }

        await(rawStore(storageId, serialized))
        await(deleteOld(currentId, ir.maxVersions))
        Done
      }
    }
  }
}
