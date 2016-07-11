package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime

import akka.actor.Scheduler
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.VersionedId
import mesosphere.marathon.core.storage.impl.BasePersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.util.{Retry, toRichFuture}
import mesosphere.util.state.zk.{Children, GetData, RichCuratorFramework}
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.async.Async.{async, await}
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = id.hashCode % 16
  def path: String = version.fold(s"/$category/$bucket/$id") { v =>
    s"/$category/$bucket/$id/versions/$v"
  }
}

case class ZkSerialized(bytes: ByteString)

class ZkPersistenceStore(client: RichCuratorFramework, maxVersions: Int)(
                        implicit mat: Materializer,
                        ctx: ExecutionContext,
                        scheduler: Scheduler,
                        val metrics: Metrics
) extends BasePersistenceStore[ZkId, ZkSerialized]() with StrictLogging {

  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  private def retry[T](name: String)(f: => Future[T]) = Retry(name, retryOn = retryOn)(f)

  override protected def rawIds(id: ZkId): Source[ZkId, NotUsed] = {
    val childrenFuture = retry(s"ZkPersistenceStore::ids($id)") {
      async {
        val buckets = await(client.children(s"/${id.category}")).children
        val children = await(Future.sequence(buckets.map(b => client.children(s"/${id.category}/$b").map(_.children))))
        children.flatten.map { child =>
          ZkId(id.category, child, None)
        }
      }
    }
    Source.fromFuture(childrenFuture).mapConcat(identity)
  }

  override protected def rawVersions(id: ZkId): Source[VersionedId[ZkId], NotUsed] = {
    val key = id.copy(version = None)
    val path = s"${key.path}/versions"
    val versions = retry(s"ZkPersistenceStore::versions($path)") {
      async {
        await(client.children(path).asTry) match {
          case Success(Children(_, _, nodes)) =>
            nodes.map { path =>
              val version = OffsetDateTime.parse(path)
              VersionedId(key.copy(version = Some(version)), version)
            }
          case Failure(_: NoNodeException) =>
            Seq.empty[VersionedId[ZkId]]
          case Failure(e) =>
            throw e
        }
      }
    }

    Source.fromFuture(versions).mapConcat(identity)
  }

  override protected def rawGet(k: ZkId): Future[Option[ZkSerialized]] = retry(s"ZkPersistenceStore::get($k)") {
      async {
        await(client.data(k.path).asTry) match {
          case Success(GetData(_, _, bytes)) =>
            Some(ZkSerialized(bytes))
          case Failure(_: NoNodeException) =>
            None
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get $k", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  override protected def rawDelete(k: ZkId, version: OffsetDateTime): Future[Done] =
    retry(s"ZkPersistenceStore::delete($k, $version)") {
      async {
        await(client.delete(k.path).asTry) match {
          case Success(_) | Failure(_: NoNodeException) => Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to delete $k", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  override protected def rawStore[V](k: ZkId, v: ZkSerialized): Future[Done] =
    retry(s"ZkPersistenceStore::store($k, $v)") {
      async {
        await(client.create(k.path, creatingParentContainersIfNeeded = true).asTry) match {
          case Success(_) | Failure(_: NodeExistsException) =>
            await(client.setData(k.path, v.bytes))
            Done
          case Failure(e) =>
            throw e
        }
      }
    }

  override protected def rawDeleteAll(k: ZkId): Future[Done] = {
    val id = k.copy(version = None)
    retry(s"ZkPersistenceStore::delete($id)") {
      client.delete(k.path, guaranteed = true, deletingChildrenIfNeeded = true).map(_ => Done)
    }
  }

  override protected[storage] def keys(): Source[ZkId, NotUsed] = {
    val sources = retry(s"ZkPersistenceStore::keys()") {
      async {
        val rootChildren = await(client.children("/").map(_.children)).map(c => ZkId(c, "", None))
        val sources = rootChildren.map(rawIds)
        sources.reduce(_.concat(_))
      }
    }
    Source.fromFuture(sources).flatMapConcat(identity)
  }
}
