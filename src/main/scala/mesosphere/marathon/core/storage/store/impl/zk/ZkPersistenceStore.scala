package mesosphere.marathon
package core.storage.store.impl.zk

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

import akka.actor.Scheduler
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Merge, Sink, Source }
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.{ StorageVersion, ZKStoreEntry }
import mesosphere.marathon.core.storage.backup.BackupItem
import mesosphere.marathon.core.storage.store.impl.{ BasePersistenceStore, CategorizedKey }
import mesosphere.marathon.storage.migration.{ Migration, StorageVersions }
import mesosphere.marathon.util.{ Retry, WorkQueue, toRichFuture }
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{ NoNodeException, NodeExistsException }
import org.apache.zookeeper.data.Stat

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = math.abs(id.hashCode % ZkId.HashBucketSize)
  def path: String = version.fold(f"/$category/$bucket%x/$id") { v =>
    f"/$category/$bucket%x/$id/${ZkId.DateFormat.format(v)}"
  }
}

object ZkId {
  val DateFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  val HashBucketSize = 16
}

case class ZkSerialized(bytes: ByteString)

class ZkPersistenceStore(
    val client: RichCuratorFramework,
    timeout: Duration,
    maxConcurrent: Int = 8,
    maxQueued: Int = 100
)(
    implicit
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler) extends BasePersistenceStore[ZkId, String, ZkSerialized]() with StrictLogging {
  private val limitRequests = WorkQueue("ZkPersistenceStore", maxConcurrent = maxConcurrent, maxQueueLength = maxQueued)

  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  private def retry[T](name: String)(f: => Future[T]) = Retry(name, retryOn = retryOn, maxDuration = timeout) {
    limitRequests(f)
  }

  @SuppressWarnings(Array("all")) // async/await
  override def storageVersion(): Future[Option[StorageVersion]] =
    retry("ZkPersistenceStore::storageVersion") {
      async {
        await(client.data(s"/${Migration.StorageVersionName}").asTry) match {
          case Success(GetData(_, _, byteString)) =>
            val wrapped = ZKStoreEntry.parseFrom(byteString.toArray)
            Some(StorageVersion.parseFrom(wrapped.getValue))
          case Failure(_: NoNodeException) =>
            None
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException("Unable to get version", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  override def setStorageVersion(storageVersion: StorageVersion): Future[Done] =
    retry(s"ZkPersistenceStore::setStorageVersion($storageVersion)") {
      async {
        val path = s"/${Migration.StorageVersionName}"
        val actualVersion = storageVersion.toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build()
        val data = ByteString(
          ZKStoreEntry.newBuilder().setValue(com.google.protobuf.ByteString.copyFrom(actualVersion.toByteArray))
          .setName(Migration.StorageVersionName)
          .setCompressed(false)
          .setUuid(com.google.protobuf.ByteString.copyFromUtf8(UUID.randomUUID().toString))
          .build.toByteArray
        )
        await(client.setData(path, data).asTry) match {
          case Success(_) =>
            Done
          case Failure(_: NoNodeException) =>
            await(client.create(path, data = Some(data)))
            Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to update storage version $storageVersion", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  override protected def rawIds(category: String): Source[ZkId, NotUsed] = {
    val childrenFuture = retry(s"ZkPersistenceStore::ids($category)") {
      async {
        val buckets = await(client.children(s"/$category").recover {
          case _: NoNodeException => Children(category, new Stat(), Nil)
        }).children
        val childFutures = buckets.map { bucket =>
          retry(s"ZkPersistenceStore::ids($category/$bucket)") {
            client.children(s"/$category/$bucket").map(_.children)
          }
        }
        val children = await(Future.sequence(childFutures))
        children.flatten.map { child =>
          ZkId(category, child, None)
        }
      }
    }
    Source.fromFuture(childrenFuture).mapConcat(identity)
  }

  @SuppressWarnings(Array("all")) // async/await
  override protected def rawVersions(id: ZkId): Source[OffsetDateTime, NotUsed] = {
    val unversioned = id.copy(version = None)
    val path = unversioned.path
    val versions = retry(s"ZkPersistenceStore::versions($path)") {
      async {
        await(client.children(path).asTry) match {
          case Success(Children(_, _, nodes)) =>
            nodes.map { path =>
              OffsetDateTime.parse(path, ZkId.DateFormat)
            }
          case Failure(_: NoNodeException) =>
            Seq.empty
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get versions of $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }
    Source.fromFuture(versions).mapConcat(identity)
  }

  @SuppressWarnings(Array("all")) // async/await
  override protected[store] def rawGet(id: ZkId): Future[Option[ZkSerialized]] =
    retry(s"ZkPersistenceStore::get($id)") {
      async {
        await(client.data(id.path).asTry) match {
          case Success(GetData(_, _, bytes)) =>
            if (bytes.nonEmpty) { // linter:ignore UseIfExpression
              Some(ZkSerialized(bytes))
            } else {
              None
            }
          case Failure(_: NoNodeException) =>
            None
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  override protected def rawDelete(id: ZkId, version: OffsetDateTime): Future[Done] =
    retry(s"ZkPersistenceStore::delete($id, $version)") {
      async {
        await(client.delete(id.copy(version = Some(version)).path).asTry) match {
          case Success(_) | Failure(_: NoNodeException) => Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to delete $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  override protected def rawDeleteCurrent(id: ZkId): Future[Done] = {
    retry(s"ZkPersistenceStore::deleteCurrent($id)") {
      async {
        await(client.setData(id.path, data = ByteString()).asTry) match {
          case Success(_) | Failure(_: NoNodeException) => Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to delete current $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override protected def rawStore[V](id: ZkId, v: ZkSerialized): Future[Done] = {
    retry(s"ZkPersistenceStore::store($id, $v)") {
      async {
        await(client.setData(id.path, v.bytes).asTry) match {
          case Success(_) =>
            Done
          case Failure(e: NoNodeException) =>
            logger.debug(s"Node for $id not found. Creating node now", e)
            await(limitRequests(client.create(
              id.path,
              creatingParentContainersIfNeeded = true, data = Some(v.bytes))).asTry) match {
              case Success(_) =>
                Done
              case Failure(_: NodeExistsException) =>
                // it could have been created by another call too... (e.g. creatingParentContainers if needed could
                // have created the node when creating the parent's, e.g. the version was created first)
                await(limitRequests(client.setData(id.path, v.bytes)))
                Done
              case Failure(e: KeeperException) =>
                throw new StoreCommandFailedException(s"Unable to store $id", e)
              case Failure(e) =>
                throw e
            }

          case Failure(e: KeeperException) =>
            logger.warn(s"Could not store under $id", e)
            throw new StoreCommandFailedException(s"Unable to store $id", e)
          case Failure(e) =>
            logger.warn(s"Could not store under $id", e)
            throw e
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override protected def rawDeleteAll(id: ZkId): Future[Done] = {
    val unversionedId = id.copy(version = None)
    retry(s"ZkPersistenceStore::delete($unversionedId)") {
      client.delete(unversionedId.path, guaranteed = true, deletingChildrenIfNeeded = true).map(_ => Done).recover {
        case _: NoNodeException =>
          Done
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  override protected[store] def allKeys(): Source[CategorizedKey[String, ZkId], NotUsed] = {
    val sources = retry("ZkPersistenceStore::keys()") {
      async {
        val rootChildren = await(client.children("/").map(_.children))
        val sources = rootChildren.map(rawIds)
        sources.foldLeft(Source.empty[ZkId])(_.concat(_))
      }
    }
    Source.fromFuture(sources).flatMapConcat(identity).map { k => CategorizedKey(k.category, k) }
  }

  @SuppressWarnings(Array("all")) // async/await
  override def backup(): Source[BackupItem, NotUsed] = {
    val ids: Source[ZkId, NotUsed] = allKeys().map(_.key)
    val versions: Source[ZkId, NotUsed] = ids.flatMapConcat(id => rawVersions(id).map(v => id.copy(version = Some(v))))
    val combined = Source.combine(ids, versions)(Merge(_))
    combined.mapAsync(maxConcurrent) { id =>
      rawGet(id).map { maybeSerialized =>
        maybeSerialized.map(serialized => BackupItem(id.category, id.id, id.version, serialized.bytes))
      }
    }.collect {
      case Some(backupItem) => backupItem
    }.concat {
      Source.fromFuture(storageVersion()).map { storedVersion =>
        val version = storedVersion.getOrElse(StorageVersions.current)
        val name = Migration.StorageVersionName
        BackupItem(name, name, None, ByteString(version.toByteArray))
      }
    }
  }

  override def restore(): Sink[BackupItem, Future[Done]] = {
    def store(item: BackupItem): Future[Done] = {
      val id = ZkId(item.category, item.key, item.version)
      rawStore(id, ZkSerialized(item.data))
    }
    def clean(): Future[Done] = {
      client.delete("/", guaranteed = true, deletingChildrenIfNeeded = true).map(_ => Done)
    }
    def setVersion(item: BackupItem): Future[Done] = {
      setStorageVersion(StorageVersion.parseFrom(item.data.toArray))
    }
    Flow[BackupItem]
      .map {
        case item if item.key == Migration.StorageVersionName => () => setVersion(item)
        case item => () => store(item)
      }
      .prepend { Source.single(() => clean()) }
      .mapAsync(1) { _.apply() } // no parallelization: first element needs to be processed before the second
      .toMat(Sink.ignore)(Keep.right)
  }
}
