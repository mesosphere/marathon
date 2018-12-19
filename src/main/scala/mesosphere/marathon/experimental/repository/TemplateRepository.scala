package mesosphere.marathon
package experimental.repository

import java.math.BigInteger
import java.nio.file.Paths
import java.security.MessageDigest

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.experimental.repository.TemplateRepositoryLike.Template
import mesosphere.marathon.experimental.storage.PathTrie
import mesosphere.marathon.state.PathId
import org.apache.zookeeper.KeeperException.NoNodeException

import scala.async.Async.{async, await}
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.reflectiveCalls
import scala.util.{Failure, Success, Try}

/**
  * This class implements a repository for templates. It uses the underlying [[ZooKeeperPersistenceStore]] and stores
  * [[Template]]s using its [[PathId]] to determine the storage location. The absolute Zookeeper path, built from the
  * [[base]], service's pathId and service's hash (see [[TemplateRepositoryLike.storePath]] method for details).
  *
  * Upon the initialization this class reads the contents of the store on it's initialization keeping everything in
  * memory using a [[PathTrie]]. This allows for synchronous reading of the templates without blocking the thread.
  * Thus all the reading/synchronous methods are served directly from the memory, all the writing calls e.g. [[create]],
  * [[delete]] are changing the underlying storage first and the in-memory copy afterwards.
  *
  * IMPORTANT NOTE:
  *
  * An interesting fact about Zookeeper: one can create a lot of znodes underneath one parent but if you try to get all of
  * them by calling [[mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore.children()]] (on the parent znode)
  * you are likely to get an error like:
  * `
  * java.io.IOException: Packet len20800020 is out of range!
  *  at org.apache.zookeeper.ClientCnxnSocket.readLength(ClientCnxnSocket.java:112)
  *  ...
  * ```
  *
  * Turns out that ZK has a packet length limit which is 4096 * 1024 bytes by default:
  * https://github.com/apache/zookeeper/blob/0cb4011dac7ec28637426cafd98b4f8f299ef61d/src/java/main/org/apache/zookeeper/client/ZKClientConfig.java#L58
  *
  * It can be altered by setting `jute.maxbuffer` environment variable. Packet in this context is an application level
  * packet containing all the children names. Some experimentation showed that each child element in that packet has ~4bytes
  * overhead for encoding. So, let's say each child znode *name* e.g. holding Marathon app definition is 50 characters long
  * (seems like a good guess given 3-4 levels of nesting e.g. `eng_dev_databases_my-favourite-mysql-instance`), we could only
  * have: 4096 * 1024 / (50 + 4) =  ~75k children nodes until we hit the exception.
  *
  * In this class we implicitly rely on the users to *not* put too many children (apps/pods) under one parent. Since that
  * number can be quite high (~75k) I think we are fine without implementing any guards.
  */
class TemplateRepository(val store: ZooKeeperPersistenceStore, val base: String)(implicit val mat: Materializer)
  extends StrictLogging with TemplateRepositoryLike {

  require(Paths.get(base).isAbsolute, "Template repository root path should be absolute")

  val trie = new PathTrie()

  /**
    * Methods takes a template and returns a [[Node]] by converting templates path Id to store path and serializing
    * templates data to a byte array.
    *
    * @param template
    * @return
    */
  def toNode(template: Template[_]): Node = Node(storePath(template.id, version(template)), ByteString(template.toProtoByteArray))

  /**
    * This implementation uses MD5 of the serialized template as described here [[https://alvinalexander.com/source-code/scala-method-create-md5-hash-of-string]]
    *
    * @param template
    * @return
    */
  override def version(template: Template[_]): String = version(template.toProtoByteArray)
  def version(bytes: Array[Byte]): String = new BigInteger(1, MessageDigest.getInstance("MD5").digest(bytes)).toString(16)

  /**
    * Convenience method that returns templates version by calling [[TemplateRepositoryLike.storePath]] method internally.
    *
    * @param template
    * @return
    */
  def storePath(template: Template[_]): String = storePath(template.id, version(template))

  /**
    * Method does the opposite conversion to the [[toNode]] method by taking in a trie [[mesosphere.marathon.experimental.storage.PathTrie.TrieNode]]
    * node (which might be `null` if the node doesn't exist) and a template instance. Returns a [[Success[T] ]] if data exists or a
    * [[Failure[NoNodeException] ]] if it doesn't.
    *
    * @param maybeData
    * @param template
    * @tparam T
    * @return
    */
  def toTemplate[T](maybeData: Array[Byte], template: Template[T]): Try[T] = maybeData match {
    case null => Failure(new NoNodeException(s"Template with path ${template.id} not found"))
    case data => Try(template.mergeFromProto(data))
  }

  /**
    * Method recursively reads the repository structure without reading node's data.
    * @param path start path. children below this path will be read recursively.
    * @return
    */
  private[this] def tree(path: String): Future[Done] = async {
    val Success(children) = await(store.children(path, absolute = true))
    val grandchildren = children.map { child =>
      trie.addPath(child)
      tree(child)
    }
    await(Future.sequence(grandchildren).map(_ => Done))
  }

  /**
    * Method fetches the data for repo's leaf nodes since that is where we keep the templates.
    * @return
    */
  private[this] def data(): Future[Done] = {
    Source(trie.getLeafs("/").asScala.toList)
      .via(store.readFlow)
      .map {
        case Success(node) => trie.addPath(node.path, node.data.toArray)
        case Failure(ex) => throw new IllegalStateException("Failed to initialize template repository. " +
          "Apparently one of the nodes was deleted during initialization.", ex)
      }
      .runWith(Sink.ignore)
  }

  /**
    * Method initializes the repository by reading all the storage content and saving it in the path trie. First,
    * tree structure is read, then, leaf nodes data is fetched. This method should be called prior to any other
    * repository method call.
    *
    * @return
    */
  def initialize(): Future[Done] = async {
    await(store.createIfAbsent(Node(base, ByteString.empty))) // Create the base path if it doesn't exist
    await(tree(base))
    await(data())
  }

  override def create(template: Template[_]): Future[String] = async {
    val data = template.toProtoByteArray
    val hash = version(data)
    val path = storePath(template.id, hash)
    val node = Node(path, ByteString(data))
    await(store.create(node))
    trie.addPath(path, data)
    hash
  }

  def read[T](template: Template[T], version: String): Try[T] =
    toTemplate(trie.getNodeData(storePath(template.id, version)), template)

  override def delete(pathId: PathId): Future[Done] = delete(pathId, version = "")
  override def delete(pathId: PathId, version: String): Future[Done] = async {
    val path = storePath(pathId, version)
    await(store.delete(path))
    trie.deletePath(path)
    Done
  }

  def children(pathId: PathId): Try[Seq[String]] = exists(pathId) match {
    case true => Try(trie.getChildren(storePath(pathId), false).asScala.to[Seq])
    case false => Failure(new NoNodeException(s"No contents for path $pathId found"))
  }

  def exists(pathId: PathId): Boolean = trie.existsNode(storePath(pathId))

  def exists(pathId: PathId, version: String) = trie.existsNode(storePath(pathId, version))
}

object TemplateRepository {

  /**
    * Helper method that take an underlying zookeeper storage and the base prefix and returns the initialized
    * repository wrapped in a future.
    *
    * @param store
    * @param base
    * @param mat
    * @return
    */
  def apply(store: ZooKeeperPersistenceStore, base: String)(implicit mat: Materializer): Future[TemplateRepository] = {
    val templateRepository = new TemplateRepository(store, base)
    templateRepository.initialize().map(_ => templateRepository)
  }
}