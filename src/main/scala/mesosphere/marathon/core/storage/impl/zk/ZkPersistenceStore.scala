package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime

import akka.actor.Scheduler
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.google.protobuf.{Message, MessageLite}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.VersionedId
import mesosphere.marathon.core.storage.impl.BasePersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.util.{Retry, toRichFuture}
import mesosphere.util.state.zk.{Children, GetData, RichCuratorFramework}
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.collection.immutable.Seq
import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = id.hashCode % 16
  def path: String = version.fold(s"/$category/$bucket/$id") { v =>
    s"/$category/$bucket/$id/versions/$v"
  }
}

case class ZkSerialized(data: Either[ByteString, MessageLite]) {
  val encoded: ByteString = ByteString(data.right.get.toByteArray)
  def decoded[T <: MessageLite](builder: Message.Builder): T =
    builder.mergeFrom(data.left.get.toArray).build().asInstanceOf[T]
}

class ZkPersistenceStore(client: RichCuratorFramework, maxVersions: Int)(
                        implicit mat: Materializer,
                        ctx: ExecutionContext,
                        scheduler: Scheduler,
                        val metrics: Metrics
) extends BasePersistenceStore[ZkId, ZkSerialized](maxVersions) with StrictLogging {

  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  private def retry[T](name: String)(f: => Future[T]) = Retry(name, retryOn = retryOn)(f)

  override protected def rawVersions(id: ZkId): Source[VersionedId[ZkId], NotUsed] = {
    val key = id.copy(version = None)
    val path = s"$key/versions"
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
            Some(ZkSerialized(Left(bytes)))
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
            await(client.setData(k.path, v.encoded))
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

  override protected[storage] def keys(): Source[ZkId, NotUsed] = ???
}
