package mesosphere.marathon.core.storage.store.impl.zk

import java.time.OffsetDateTime
import java.util.UUID

import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.{ StorageVersion, ZKStoreEntry }
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.migration.Migration
import mesosphere.marathon.core.storage.store.impl.{ BasePersistenceStore, CategorizedKey }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.util.{ Retry, Timeout, toRichFuture }
import mesosphere.util.{ CapConcurrentExecutions, CapConcurrentExecutionsMetrics }
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{ NoNodeException, NodeExistsException }
import org.apache.zookeeper.data.Stat

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

class ZkPersistenceStore(
    val client: RichCuratorFramework,
    timeout: Duration,
    maxConcurrent: Int = 8,
    maxQueued: Int = 100 // scalastyle:off magic.number
)(
    implicit
    mat: Materializer,
    actorRefFactory: ActorRefFactory,
    ctx: ExecutionContext,
    scheduler: Scheduler,
    val metrics: Metrics
) extends BasePersistenceStore[ZkId, String, ZkSerialized]() with StrictLogging {
  private val limitRequests = CapConcurrentExecutions(
    CapConcurrentExecutionsMetrics(metrics, getClass),
    actorRefFactory,
    s"ZkPersistenceStore_${client}_${UUID.randomUUID}".replaceAll("\\(|\\)|/", "_"),
    maxConcurrent = maxConcurrent,
    maxQueued = maxQueued)

  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  private def retry[T](name: String)(f: => Future[T]) =
    Timeout(timeout) {
      Retry(name, retryOn = retryOn) {
        limitRequests(f)
      }
    }

  override private[storage] def storageVersion(): Future[Option[StorageVersion]] =
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

  /** Update the version of the storage */
  override private[storage] def setStorageVersion(storageVersion: StorageVersion): Future[Done] =
    retry(s"ZkPersistenceStore::setStorageVersion($storageVersion)") {
      async {
        val path = s"/${Migration.StorageVersionName}"
        val actualVersion = storageVersion.toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build()
        val data = ByteString(
          ZKStoreEntry.newBuilder().setValue(com.google.protobuf.ByteString.copyFrom(actualVersion.toByteArray))
          .setName(Migration.StorageVersionName)
          .setCompressed(false)
          .build.toByteArray
        )
        await(client.setData(path, data).asTry) match {
          case Success(_) => Done
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

  override protected[store] def rawGet(id: ZkId): Future[Option[ZkSerialized]] =
    retry(s"ZkPersistenceStore::get($id)") {
      async {
        await(client.data(id.path).asTry) match {
          case Success(GetData(_, _, bytes)) =>
            if (bytes.nonEmpty) {
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

  override protected def rawStore[V](id: ZkId, v: ZkSerialized): Future[Done] = {
    retry(s"ZkPersistenceStore::store($id, $v)") {
      async {
        await(client.setData(id.path, v.bytes).asTry) match {
          case Success(_) =>
            Done
          case Failure(_: NoNodeException) =>
            await(limitRequests(client.create(
              id.path,
              creatingParentContainersIfNeeded = true, data = Some(v.bytes))).asTry) match {
              case Success(_) =>
                Done
              case Failure(e: NodeExistsException) =>
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
            throw new StoreCommandFailedException(s"Unable to store $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }
  }

  override protected def rawDeleteAll(id: ZkId): Future[Done] = {
    val unversionedId = id.copy(version = None)
    retry(s"ZkPersistenceStore::delete($unversionedId)") {
      client.delete(unversionedId.path, guaranteed = true, deletingChildrenIfNeeded = true).map(_ => Done).recover {
        case _: NoNodeException =>
          Done
      }
    }
  }

  override protected[store] def allKeys(): Source[CategorizedKey[String, ZkId], NotUsed] = {
    val sources = retry(s"ZkPersistenceStore::keys()") {
      async {
        val rootChildren = await(client.children("/").map(_.children))
        val sources = rootChildren.map(rawIds)
        sources.foldLeft(Source.empty[ZkId])(_.concat(_))
      }
    }
    Source.fromFuture(sources).flatMapConcat(identity).map { k => CategorizedKey(k.category, k) }
  }
}
