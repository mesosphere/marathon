package mesosphere.marathon
package experimental.repository

import java.nio.file.Paths

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.experimental.repository.TemplateRepositoryLike.Template
import mesosphere.marathon.experimental.storage.PathTrie
import mesosphere.marathon.state.PathId
import mesosphere.util.NamedExecutionContext
import org.apache.zookeeper.KeeperException.NoNodeException

import scala.async.Async.{async, await}
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.language.reflectiveCalls

/**
  * This is partially synchronous version of the [[AsyncTemplateRepository]] class. For more details on how this
  * repository is saving the data in the Zookeeper see the comments to the [[AsyncTemplateRepository]] class.

  * However, unlike it's asynchronous counterpart, this class reads the contents of the store on it's initialization
  * keeping everything in memory using a [[PathTrie]]. This allows for synchronous reading of the templates via
  * additional methods [[readSync]], [[contentsSync]] and [[existsSync]]. In fact, the asynchronous versions of these
  * methods are calling their synchronous counterparts wrapping the calls into a future.
  *
  * All the reading/synchronous methods are served directly from the memory, all the writing calls e.g. [[create]],
  * [[delete]] are changing the underlying storage first and the in-memory copy afterwards.
  *
  * @param store
  * @param base
  * @param mat
  */
class SyncTemplateRepository(val store: ZooKeeperPersistenceStore, val base: String)
                            (implicit val mat: Materializer)
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
  def toNode(template: Template[_]) = Node(storePath(template), ByteString(template.toProtoByteArray))

  /**
    * Method does the opposite conversion to the [[toNode]] method by taking in a trie [[mesosphere.marathon.experimental.storage.PathTrie.TrieNode]]
    * node (which might be `null` if the node doesn't exist) and a template instance. Returns a [[Success[T] ]] if data exists or a
    * [[Failure[NoNodeException] ]] if it doesn't.
    *
    * @param maybeNode
    * @param template
    * @tparam T
    * @return
    */
  def toTemplate[T](maybeNode: PathTrie.TrieNode, template: Template[T]): Try[T] = maybeNode match {
    case null => Failure(new NoNodeException(s"Template with path ${template.id} not found"))
    case node => Success(template.mergeFromProto(node.getData))
  }

  // We define an extra execution context to limit the amount of futures we execute concurrently during initialization.
  implicit val ec: ExecutionContext = NamedExecutionContext.fixedThreadPoolExecutionContext(
    Runtime.getRuntime.availableProcessors(),
    "sync-template-repo")

  /**
    * Method recursively reads the repository structure without reading node's data.
    * @param path start path. children below this path will be read recursively.
    * @return
    */
  private[this] def tree(path: String): Future[Done] = async {
    val children = await(store.children(path, absolute = true))
    val grandchildren = children.map{ child =>
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
      .map(maybeNode => maybeNode match {
        case Success(node) => trie.addPath(node.path, node.data.toArray)
        case Failure(ex) => throw new IllegalStateException("Failed to initialize template repository. " +
          "Apparently one of the nodes was deleted during initialization.", ex)
      })
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

  override def create(template: Template[_]): Future[Done] = {
    store
      .create(toNode(template))
      .map(_ => trie.addPath(storePath(template), template.toProtoByteArray))
      .map(_ => Done)
  }

  override def read[T](template: Template[T], version: String): Future[T] = Future.fromTry(readSync(template, version))
  def readSync[T](template: Template[T], version: String): Try[T] = {
    toTemplate(
      trie.getNode(storePath(template.id, version)),
      template)
  }

  override def delete(template: Template[_]): Future[Done] = delete(template.id, version(template))
  override def delete(pathId: PathId): Future[Done] = delete(pathId, version = "")
  def delete(pathId: PathId, version: String): Future[Done] = {
    store
      .delete(storePath(pathId, version))
      .map(trie.deletePath(_))
      .map(_ => Done)
  }

  override def contents(pathId: PathId): Future[marathon.Seq[String]] = Future.fromTry(contentsSync(pathId))
  def contentsSync(pathId: PathId): Try[Seq[String]] = {
    Try(trie.getChildren(storePath(pathId), true))
      .map(set => set.asScala.to[Seq])
      .orElse(Failure(new NoNodeException(s"No contents for path $pathId found")))
  }

  def existsSync[T](template: Template[T]): Boolean = trie.existsNode(storePath(template))

  override def exists(template: Template[_]): Future[Boolean] = Future(existsSync(template))
  override def exists(pathId: PathId): Future[Boolean] = Future(existsSync(pathId))
  def existsSync(pathId: PathId): Boolean = trie.existsNode(storePath(pathId))
}
