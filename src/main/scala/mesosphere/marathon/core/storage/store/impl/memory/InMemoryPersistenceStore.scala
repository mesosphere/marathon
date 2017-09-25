package mesosphere.marathon
package core.storage.store.impl.memory

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }
import java.time.OffsetDateTime

import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.ByteString
import akka.{ Done, NotUsed }
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.backup.BackupItem
import mesosphere.marathon.core.storage.store.impl.{ BasePersistenceStore, CategorizedKey }
import mesosphere.marathon.io.IO
import mesosphere.marathon.storage.migration.{ Migration, StorageVersions }
import mesosphere.marathon.util.Lock

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

case class RamId(category: String, id: String, version: Option[OffsetDateTime])

case class Identity(value: Any)

class InMemoryPersistenceStore(implicit
  protected val mat: Materializer,
  ctx: ExecutionContext)
    extends BasePersistenceStore[RamId, String, Identity] {
  val entries = TrieMap[RamId, Identity]()
  val version = Lock(StorageVersions.current.toBuilder)

  override def storageVersion(): Future[Option[StorageVersion]] = {
    Future.successful(Some(version(_.build())))
  }

  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] = {
    version(_.mergeFrom(storageVersion))
    Future.successful(Done)
  }

  override protected def rawIds(category: String): Source[RamId, NotUsed] = {
    val ids = entries.keySet.filter(_.category == category)
    // we need to list the id even if there is no current version.
    Source(ids.groupBy(_.id).flatMap(_._2.headOption))
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
    val versions = entries.collect {
      case (RamId(category, rid, Some(v)), _) if category == id.category && id.id == rid => v
    }(collection.breakOut)
    Source(versions)
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

  override def backup(): Source[BackupItem, NotUsed] = {
    Source.fromIterator(() => entries.iterator.map {
      case (key, value) =>
        BackupItem(key.category, key.id, key.version, ByteString(InMemoryPersistenceStore.objectToByteArray(value.value)))
    }).concat {
      Source.single {
        val name = Migration.StorageVersionName
        BackupItem(name, name, None, ByteString(version(_.build().toByteArray)))
      }
    }
  }

  override def restore(): Sink[BackupItem, Future[Done]] = {
    def store(item: BackupItem): Done = {
      InMemoryPersistenceStore.byteArrayToObject[AnyRef](item.data.toArray) match {
        case Some(value) => entries.put(RamId(item.category, item.key, item.version), Identity(value))
        case None => throw new IllegalArgumentException(s"Could not read object: ${item.key}=${item.data}")
      }
      Done
    }
    def clean(): Done = {
      entries.clear()
      Done
    }
    def setVersion(item: BackupItem): Done = {
      version(_.mergeFrom(item.data.toArray))
      Done
    }
    Flow[BackupItem]
      .map {
        case item if item.key == Migration.StorageVersionName => setVersion(item)
        case item => store(item)
      }
      .prepend { Source.single(clean()) }
      .toMat(Sink.ignore)(Keep.right)
  }

  override def sync(): Future[Done] = Future.successful(Done)
}

object InMemoryPersistenceStore {

  def objectToByteArray(any: Any): Array[Byte] = {
    IO.using(new ByteArrayOutputStream()) { stream =>
      val obj = new ObjectOutputStream(stream)
      obj.writeObject(any)
      obj.close()
      stream.toByteArray
    }
  }

  def byteArrayToObject[T](bytes: Array[Byte])(implicit ClassT: ClassTag[T]): Option[T] = {
    IO.using(new ObjectInputStream(new ByteArrayInputStream(bytes))) { stream =>
      stream.readObject() match {
        case ClassT(t) => Some(t)
        case _ => None
      }
    }
  }
}
