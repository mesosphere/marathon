package mesosphere.marathon.core.storage.impl.zk

import akka.actor.Scheduler
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }
import mesosphere.marathon.util.{ Retry, toRichFuture }
import mesosphere.util.state.zk.{ Children, GetData, RichCuratorFramework }
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.NoNodeException

import scala.async.Async.{ async, await }
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

/**
  * Marker class for Zookeeper Ids
  *
  * @param id The id in directory format, must be absolute.
  */
case class ZkId(id: String) extends AnyVal

/**
  * Marker class for Zookeeper values serialized as ByteStrings
  * TODO: Consider using proto messages instead?
  *
  * @param bytes The proto serialized bytes.
  */
case class ZkSerialized(bytes: ByteString) extends AnyVal

class ZkPersistenceStore(client: RichCuratorFramework)(implicit
  ctx: ExecutionContext,
  mat: ActorMaterializer,
  scheduler: Scheduler)
    extends PersistenceStore[ZkId, ZkSerialized] with StrictLogging {
  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  override def ids[Id, V](parent: Id)(implicit ir: IdResolver[Id, ZkId, V, ZkSerialized]): Source[Id, NotUsed] = {
    val childrenFuture = Retry(s"ZkPersistenceStore::ids($parent)", retryOn = retryOn) {
      async {
        val children = await(client.children(ir.toStorageId(parent).id).asTry)
        children match {
          case Success(Children(_, _, nodes)) =>
            nodes.map(zkPath => ir.fromStorageId(ZkId(zkPath)))
          case Failure(_: NoNodeException) =>
            Seq.empty[Id]
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get children of: $parent", e)
          case Failure(e) =>
            throw e
        }
      }
    }
    Source.fromFuture(childrenFuture).mapConcat(identity)
  }

  override protected def set(k: ZkId, v: ZkSerialized): Future[Done] = {
    Retry(s"ZkPersistenceStore::set($k)", retryOn = retryOn) {
      client.setData(k.id, v.bytes).map(_ => Done).recover {
        case e: KeeperException => throw new StoreCommandFailedException(s"Unable to update: ${k.id}", e)
      }
    }
  }

  override protected def createRaw(k: ZkId, v: ZkSerialized): Future[Done] = {
    Retry(s"ZkPersistenceStore::create($k)", retryOn = retryOn) {
      client.create(
        k.id,
        Some(v.bytes),
        creatingParentContainersIfNeeded = true,
        creatingParentsIfNeeded = true).map(_ => Done).recover {
          case e: KeeperException => throw new StoreCommandFailedException(s"Unable to create: ${k.id}", e)
        }
    }
  }

  override def get[Id, V](k: Id)(implicit
    ir: IdResolver[Id, ZkId, V, ZkSerialized],
    um: Unmarshaller[ZkSerialized, V]): Future[Option[V]] =
    Retry(s"ZkPersistenceStore::get($k)", retryOn = retryOn) {
      async {
        val data = await(client.data(ir.toStorageId(k).id).asTry)
        data match {
          case Success(GetData(_, _, bytes)) =>
            Some(await(Unmarshal(ZkSerialized(bytes)).to[V]))
          case Failure(_: NoNodeException) =>
            None
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get $k", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  override def delete[Id, V](id: Id)(implicit ir: IdResolver[Id, ZkId, V, ZkSerialized]): Future[Done] =
    Retry(s"ZkPersistenceStore::delete($id)", retryOn = retryOn) {
      async {
        val path = ir.toStorageId(id)
        await(client.delete(path.id).asTry) match {
          case Success(_) | Failure(_: NoNodeException) => Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to delete $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }
}
