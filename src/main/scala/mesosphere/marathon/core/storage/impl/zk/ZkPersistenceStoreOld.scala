/*package mesosphere.marathon.core.storage.impl.zk

import akka.actor.Scheduler
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.core.storage.BasePersistenceStore
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
case class ZkId2(id: String) extends AnyVal

/**
  * Marker class for Zookeeper values serialized as ByteStrings
  * TODO: Consider using proto messages instead?
  *
  * @param bytes The proto serialized bytes.
  */
case class ZkSerialized2(bytes: ByteString) extends AnyVal

class ZkPersistenceStore2(client: RichCuratorFramework)(implicit
  override val ctx: ExecutionContext,
  val mat: ActorMaterializer,
  scheduler: Scheduler)
    extends BasePersistenceStore[ZkId2, ZkSerialized2] with StrictLogging {

  private val retryOn: Retry.RetryOnFn = {
    case _: KeeperException.ConnectionLossException => true
    case _: KeeperException => false
    case NonFatal(_) => true
  }

  override protected def rawDelete(id: ZkId): Future[Done] =
    Retry(s"ZkPersistenceStore::delete($id)", retryOn = retryOn) {
      async {
        await(client.delete(id.id).asTry) match {
          case Success(_) | Failure(_: NoNodeException) => Done
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to delete $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  override protected[storage] def rawGet(id: ZkId): Future[Option[ZkSerialized]] =
    Retry(s"ZkPersistenceStore::get($id)", retryOn = retryOn) {
      async {
        val data = await(client.data(id.id).asTry)
        data match {
          case Success(GetData(_, _, bytes)) =>
            Some(ZkSerialized(bytes))
          case Failure(_: NoNodeException) =>
            None
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get $id", e)
          case Failure(e) =>
            throw e
        }
      }
    }

  override protected[storage] def keys(): Source[ZkId, NotUsed] = {
    def keys(path: ZkId): Source[ZkId, NotUsed] = {
      rawIds(path).flatMapConcat { child =>
        val childId = ZkId(if (path.id == "/") s"/${child.id}" else s"${path.id}/${child.id}")
        if (path.id != "/") {
          Source.single(childId).concat(keys(childId))
        } else {
          keys(childId)
        }
      }
    }
    keys(ZkId("/"))
  }

  override protected def rawIds(parent: ZkId): Source[ZkId, NotUsed] = {
    val childrenFuture = Retry(s"ZkPersistenceStore::ids($parent)", retryOn = retryOn) {
      async {
        val children = await(client.children(parent.id).asTry)
        children match {
          case Success(Children(_, _, nodes)) =>
            nodes.map(ZkId(_))
          case Failure(_: NoNodeException) =>
            Seq.empty[ZkId]
          case Failure(e: KeeperException) =>
            throw new StoreCommandFailedException(s"Unable to get children of: $parent", e)
          case Failure(e) =>
            throw e
        }
      }
    }
    Source.fromFuture(childrenFuture).mapConcat(identity)
  }

  override protected[storage] def rawSet(k: ZkId, v: ZkSerialized): Future[Done] = {
    Retry(s"ZkPersistenceStore::set($k)", retryOn = retryOn) {
      client.setData(k.id, v.bytes).map(_ => Done).recover {
        case e: KeeperException => throw new StoreCommandFailedException(s"Unable to update: ${k.id}", e)
      }
    }
  }

  override protected def rawCreate(k: ZkId, v: ZkSerialized): Future[Done] = {
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

  override def toString: String = s"ZkPersistenceStore($client)"
}
*/
