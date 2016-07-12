package mesosphere.marathon.core.storage.impl.zk

import java.time.OffsetDateTime

import akka.actor.Scheduler
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.google.protobuf.MessageLite
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.{ CategorizedKey, MarathonProto, MarathonState }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.util.{ Retry, toRichFuture }
import mesosphere.util.state.zk.{ Children, GetData, RichCuratorFramework }
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.data.Stat

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

case class ZkId(category: String, id: String, version: Option[OffsetDateTime]) {
  private val bucket = id.hashCode % 16
  def path: String = version.fold(s"/$category/$bucket/$id") { v =>
    s"/$category/$bucket/$id/versions/$v"
  }
}

case class ZkSerialized(bytes: ByteString)

trait ZkSerialization {
  implicit def zkMarshal[Proto <: MessageLite, T <: MarathonState[Proto]]: Marshaller[MarathonState[Proto], ZkSerialized] =
    Marshaller.opaque { (a: MarathonState[Proto]) =>
      ZkSerialized(ByteString(a.toProto.toByteArray))
    }

  def zkUnmarshaller[Proto <: MessageLite, T <: MarathonState[Proto]](proto: MarathonProto[Proto, T]): Unmarshaller[ZkSerialized, T] =
    Unmarshaller.strict { (a: ZkSerialized) => proto.fromProtoBytes(a.bytes) }
}

class ZkPersistenceStore(val client: RichCuratorFramework)(
    implicit
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler,
    val metrics: Metrics
) extends BasePersistenceStore[ZkId, String, ZkSerialized]() with StrictLogging {

  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  private def retry[T](name: String)(f: => Future[T]) = Retry(name, retryOn = retryOn)(f)

  override protected def rawIds(category: String): Source[ZkId, NotUsed] = {
    val childrenFuture = retry(s"ZkPersistenceStore::ids($category)") {
      async {
        val buckets = await(client.children(s"/$category").recover {
          case _: NoNodeException => Children(category, new Stat(), Nil)
        }).children
        val children = await(Future.sequence(buckets.map(b => client.children(s"/$category/$b").map(_.children))))
        children.flatten.map { child =>
          ZkId(category, child, None)
        }
      }
    }
    Source.fromFuture(childrenFuture).mapConcat(identity)
  }

  override protected def rawVersions(id: ZkId): Source[OffsetDateTime, NotUsed] = {
    val key = id.copy(version = None)
    val path = s"${key.path}/versions"
    val versions = retry(s"ZkPersistenceStore::versions($path)") {
      async {
        await(client.children(path).asTry) match {
          case Success(Children(_, _, nodes)) =>
            nodes.map { path =>
              OffsetDateTime.parse(path)
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

  override protected[storage] def rawGet(k: ZkId): Future[Option[ZkSerialized]] =
    retry(s"ZkPersistenceStore::get($k)") {
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
        await(client.delete(k.copy(version = Some(version)).path).asTry) match {
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
        await(client.setData(k.path, v.bytes).asTry) match {
          case Success(_) =>
            Done
          case Failure(_: NoNodeException) =>
            await(client.create(k.path, creatingParentContainersIfNeeded = true))
            await(client.setData(k.path, v.bytes))
            Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to store $k", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  override protected def rawDeleteAll(k: ZkId): Future[Done] = {
    val id = k.copy(version = None)
    retry(s"ZkPersistenceStore::delete($id)") {
      client.delete(k.path, guaranteed = true, deletingChildrenIfNeeded = true).map(_ => Done).recover {
        case _: NoNodeException =>
          Done
      }
    }
  }

  override protected[storage] def keys(): Source[CategorizedKey[String, ZkId], NotUsed] = {
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
