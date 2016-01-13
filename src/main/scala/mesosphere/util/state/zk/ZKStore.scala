package mesosphere.util.state.zk

import java.util.UUID

import com.fasterxml.uuid.impl.UUIDUtil
import com.google.protobuf.{ ByteString, InvalidProtocolBufferException }
import com.twitter.util.{ Future => TWFuture }
import com.twitter.zk.{ ZNode, ZkClient }
import mesosphere.marathon.io.IO
import mesosphere.marathon.{ Protos, StoreCommandFailedException }
import mesosphere.util.ThreadPoolContext
import mesosphere.util.state.zk.ZKStore._
import mesosphere.util.state.{ PersistentEntity, PersistentStore, PersistentStoreManagement }
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{ NoNodeException, NodeExistsException }
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future, Promise }

case class CompressionConf(enabled: Boolean, sizeLimit: Long)

class ZKStore(val client: ZkClient, root: ZNode, compressionConf: CompressionConf) extends PersistentStore
    with PersistentStoreManagement {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] implicit val ec = ExecutionContext.Implicits.global

  /**
    * Fetch data and return entity.
    * The entity is returned also if it is not found in zk, since it is needed for the store operation.
    */
  override def load(key: ID): Future[Option[ZKEntity]] = {
    val node = root(key)
    require(node.parent == root, s"Nested paths are not supported: $key!")
    node.getData().asScala
      .map { data => Some(ZKEntity(node, ZKData(data.bytes), Some(data.stat.getVersion))) }
      .recover { case ex: NoNodeException => None }
      .recover(exceptionTransform(s"Could not load key $key"))
  }

  override def create(key: ID, content: IndexedSeq[Byte]): Future[ZKEntity] = {
    val node = root(key)
    require(node.parent == root, s"Nested paths are not supported: $key")
    val data = ZKData(key, UUID.randomUUID(), content)
    node.create(data.toProto(compressionConf).toByteArray).asScala
      .map { n => ZKEntity(n, data, Some(0)) } //first version after create is 0
      .recover(exceptionTransform(s"Can not create entity $key"))
  }

  /**
    * This will store a previously fetched entity.
    * The entity will be either created or updated, depending on the read state.
    * @return Some value, if the store operation is successful otherwise None
    */
  override def update(entity: PersistentEntity): Future[ZKEntity] = {
    val zk = zkEntity(entity)
    val version = zk.version.getOrElse (
      throw new StoreCommandFailedException(s"Can not store entity $entity, since there is no version!")
    )
    zk.node.setData(zk.data.toProto(compressionConf).toByteArray, version).asScala
      .map { data => zk.copy(version = Some(data.stat.getVersion)) }
      .recover(exceptionTransform(s"Can not update entity $entity"))
  }

  /**
    * Delete an entry with given identifier.
    */
  override def delete(key: ID): Future[Boolean] = {
    val node = root(key)
    require(node.parent == root, s"Nested paths are not supported: $key")
    node.exists().asScala
      .flatMap { d => node.delete(d.stat.getVersion).asScala.map(_ => true) }
      .recover { case ex: NoNodeException => false }
      .recover(exceptionTransform(s"Can not delete entity $key"))
  }

  override def allIds(): Future[Seq[ID]] = {
    root.getChildren().asScala
      .map(_.children.map(_.name))
      .recover(exceptionTransform("Can not list all identifiers"))
  }

  private[this] def exceptionTransform[T](errorMessage: String): PartialFunction[Throwable, T] = {
    case ex: KeeperException => throw new StoreCommandFailedException(errorMessage, ex)
  }

  private[this] def zkEntity(entity: PersistentEntity): ZKEntity = {
    entity match {
      case zk: ZKEntity => zk
      case _            => throw new IllegalArgumentException(s"Can not handle this kind of entity: ${entity.getClass}")
    }
  }

  private[this] def createPath(path: ZNode): Future[ZNode] = {
    def nodeExists(node: ZNode): Future[Boolean] = node.exists().asScala
      .map(_ => true)
      .recover { case ex: NoNodeException => false }
      .recover(exceptionTransform("Can not query for exists"))

    def createNode(node: ZNode): Future[ZNode] = node.create().asScala
      .recover { case ex: NodeExistsException => node }
      .recover(exceptionTransform("Can not create"))

    def createPath(node: ZNode): Future[ZNode] = {
      nodeExists(node).flatMap {
        case true  => Future.successful(node)
        case false => createPath(node.parent).flatMap(_ => createNode(node))
      }
    }
    createPath(path)
  }

  override def initialize(): Future[Unit] = createPath(root).map(_ => ())
}

case class ZKEntity(node: ZNode, data: ZKData, version: Option[Int] = None) extends PersistentEntity {
  override def id: String = node.name
  override def withNewContent(updated: IndexedSeq[Byte]): PersistentEntity = copy(data = data.copy(bytes = updated))
  override def bytes: IndexedSeq[Byte] = data.bytes
}

case class ZKData(name: String, uuid: UUID, bytes: IndexedSeq[Byte] = Vector.empty) {
  def toProto(compression: CompressionConf): Protos.ZKStoreEntry = {
    val (data, compressed) =
      if (compression.enabled && bytes.length > compression.sizeLimit) (IO.gzipCompress(bytes.toArray), true)
      else (bytes.toArray, false)
    Protos.ZKStoreEntry.newBuilder()
      .setName(name)
      .setUuid(ByteString.copyFromUtf8(uuid.toString))
      .setCompressed(compressed)
      .setValue(ByteString.copyFrom(data))
      .build()
  }
}
object ZKData {
  import IO.{ gzipUncompress => uncompress }
  def apply(bytes: Array[Byte]): ZKData = {
    try {
      val proto = Protos.ZKStoreEntry.parseFrom(bytes)
      val content = if (proto.getCompressed) uncompress(proto.getValue.toByteArray) else proto.getValue.toByteArray
      new ZKData(proto.getName, UUIDUtil.uuid(proto.getUuid.toByteArray), content)
    }
    catch {
      case ex: InvalidProtocolBufferException =>
        throw new StoreCommandFailedException(s"Can not deserialize Protobuf from ${bytes.length}", ex)
    }
  }
}

object ZKStore {
  implicit class Twitter2Scala[T](val twitterF: TWFuture[T]) extends AnyVal {
    def asScala: Future[T] = {
      val promise = Promise[T]()
      twitterF.onSuccess(promise.success(_))
      twitterF.onFailure(promise.failure(_))
      promise.future
    }
  }
}
