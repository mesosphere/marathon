package mesosphere.marathon
package core.storage.zookeeper

import java.nio.file.Paths

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore._
import mesosphere.marathon.metrics.Metrics
import org.apache.zookeeper.KeeperException.NoNodeException

import scala.collection.JavaConverters
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

/**
  * An implementation of the [[PersistenceStore]] interface.
  *
  * It utilises Apache Curator async methods under the hood from the org.apache.curator.curator-x-async library. This
  * is similar to synchronous methods with [[org.apache.curator.framework.api.Backgroundable]] callback however, it
  * uses a somewhat cleaner [asynchronous DSL](https://curator.apache.org/curator-x-async/index.html).
  *
  * Create, update, read and delete operation are offered as akka-stream Flows. Every Flow will offer an output signalising
  * that the input element was handled successfully. E.g. a [[createFlow]] flow takes a stream of nodes to store and outputs
  * a stream of node paths indicating that the node was successfully stored.
  *
  * For CRUD operations the submitted stream elements are handled with configured parallelism. For create and update
  * operations the implementation guaranties that if multiple operations update the same path, the order of writes is the
  * same as in the input stream.
  *
  * Transaction support is implemented and four transaction operations are supported: [[CreateOp]], [[UpdateOp]],
  * [[DeleteOp]] and [[CheckOp]]. All transaction operations has to be successful for the transaction to commit successfully.
  *
  * @param factory an instance of [[AsyncCuratorBuilderFactory]]
  * @param parallelism parallelism level for CRUD operations
  * @param ec execution context
  */
class ZooKeeperPersistenceStore(
    metrics: Metrics,
    factory: AsyncCuratorBuilderFactory,
    parallelism: Int = 10)(implicit ec: ExecutionContext)
  extends PersistenceStore with StrictLogging {

  private[this] val createMetric = metrics.counter("debug.zookeeper.operations.create")
  private[this] val readMetric = metrics.counter("debug.zookeeper.operations.read")
  private[this] val updateMetric = metrics.counter("debug.zookeeper.operations.update")
  private[this] val deleteMetric = metrics.counter("debug.zookeeper.operations.delete")
  private[this] val childrenMetric = metrics.counter("debug.zookeeper.operations.children")
  private[this] val existsMetric = metrics.counter("debug.zookeeper.operations.exists")
  private[this] val transactionMetric = metrics.counter("debug.zookeeper.operations.transaction")
  private[this] val transactionOpCountMetric = metrics.counter("debug.zookeeper.transaction-operations")

  /**
    * A Flow for saving nodes to the store. It takes a stream of nodes and returns a stream of node keys
    * that were successfully stored.
    *
    * By default persistent nodes with empty ACLs are created. If the path is nested parent nodes will be also
    * created if they don't already exist. If there is already a node with the same path a [[org.apache.zookeeper.KeeperException.NodeExistsException]]
    * is thrown.
    *
    * @return
    */
  override def createFlow: Flow[Node, String, NotUsed] =
    Flow[Node]
      // `groupBy(path.hashCode % parallelism)` makes sure that updates to the same path always land in the same
      // sub-stream, thus keeping the order of writes to the same path, even with parallelism > 1
      .groupBy(parallelism, node => Math.abs(node.path.hashCode) % parallelism)
      .mapAsync(1)(node => create(node))
      .mergeSubstreams

  override def create(node: Node): Future[String] = {
    logger.debug(s"Creating a node at ${node.path}")
    createMetric.increment()
    factory
      .create()
      .forPath(node.path, node.data.toArray)
      .toScala
  }

  /**
    * A Flow for reading nodes from the store. It takes a stream of node paths and returns a stream of Try[Node] elements.
    * It's a Success[Node] for existing nodes or Failure(e) for non-existing nodes where e is an instance of
    * [[org.apache.zookeeper.KeeperException.NoNodeException]]. The exception contains the path to the node which
    * simplifies handling failed reads. For other exceptions stream is completed with a failure.
    *
    * @return
    */
  override def readFlow: Flow[String, Try[Node], NotUsed] =
    Flow[String]
      .mapAsync(parallelism)(path => read(path))

  override def read(path: String): Future[Try[Node]] = {
    logger.debug(s"Reading a node at $path")
    readMetric.increment()
    factory
      .getData()
      .forPath(path)
      .toScala
      .map(bytes => Try(Node(path, ByteString(bytes))))
      .recover {
        case e: NoNodeException => Failure(e) // re-throw exception in all other cases
      }
  }

  /**
    * A Flow for updating nodes in the store. It takes a stream of nodes and returns a stream of paths to indicate
    * a successful update operation for the returned path. Only existing nodes can be updated. If a node with the path
    * does not exist a [[org.apache.zookeeper.KeeperException.NoNodeException]] is thrown.
    *
    * @return
    */
  override def updateFlow: Flow[Node, String, NotUsed] =
    Flow[Node]
      // `groupBy(path.hashCode % parallelism)` makes sure that updates to the same path always land in the same
      // sub-stream, thus keeping the order of writes to the same path, even with parallelism > 1
      .groupBy(parallelism, node => Math.abs(node.path.hashCode) % parallelism)
      .mapAsync(1)(node => update(node))
      .mergeSubstreams

  override def update(node: Node): Future[String] = {
    logger.debug(s"Updating a node at ${node.path}")
    updateMetric.increment()
    factory
      .setData()
      .forPath(node.path, node.data.toArray)
      .toScala
      .map(_ => node.path)
  }

  /**
    * A Flow for deleting nodes from the repository. It takes a stream of paths and returns a stream of paths to indicate
    * a successful deletion operation for the returned path. If the node doesn't exist the operation is still considered
    * successful.
    *
    * @return
    */
  override def deleteFlow: Flow[String, String, NotUsed] =
    Flow[String]
      .mapAsync(parallelism)(path => delete(path))

  override def delete(path: String): Future[String] = {
    logger.debug(s"Deleting a node at $path")
    deleteMetric.increment()
    factory
      .delete()
      .forPath(path)
      .toScala
      .map(_ => path)
  }

  /**
    * A Flow returning a stream of children for the given parent nodes.
    *
    * @return
    */
  override def childrenFlow(absolute: Boolean): Flow[String, Seq[String], NotUsed] =
    Flow[String]
      .mapAsync(parallelism)(path => children(path, absolute))

  override def children(path: String, absolute: Boolean): Future[Seq[String]] = {
    logger.debug(s"Getting children at $path")
    childrenMetric.increment()
    factory
      .children()
      .forPath(path).toScala
      .map(JavaConverters.asScalaBuffer(_).to[Seq])
      .map(children => children.map(child => if (absolute) Paths.get(path, child).toString else child))
  }

  override def existsFlow: Flow[String, Boolean, NotUsed] =
    Flow[String]
      .mapAsync(parallelism)(path => exists(path))

  override def exists(path: String): Future[Boolean] = {
    logger.debug(s"Checking node existence for $path")
    existsMetric.increment()
    factory
      .checkExists()
      .forPath(path).toScala
      .map(stat => if (stat == null) false else true)
  }

  override def sync(path: String = "/"): Future[Done] = {
    logger.debug(s"Syncing nodes for path $path")
    factory
      .sync()
      .forPath(path).toScala
      .map(_ => Done)
  }

  /**
    * Method takes a list of transaction [[mesosphere.marathon.core.storage.zookeeper.PersistenceStore.StoreOp]] operations
    * and submits them. An exception is thrown if one of the operations fail. Currently only create, update, delete and
    * check operations are supported.
    *
    * Note: Due to current state of the underlying Curator API, [[mesosphere.marathon.core.storage.zookeeper.PersistenceStore.CreateOp]]s
    * can't create parent nodes for nested paths if parent nodes does not exist.
    *
    * @param operations a list of transaction operations
    * @return
    */
  override def transaction(operations: Seq[StoreOp]): Future[Done] = {
    logger.debug(s"Submitting a transaction with ${operations.size} operations")

    transactionOpCountMetric.increment(operations.size.toLong)
    transactionMetric.increment()

    val transactionOps = operations.map {
      case CreateOp(node) => factory.transactionOpCreate().forPath(node.path, node.data.toArray)
      case UpdateOp(node) => factory.transactionOpSetData().forPath(node.path, node.data.toArray)
      case DeleteOp(path) => factory.transactionOpDelete().forPath(path)
      case CheckOp(path) => factory.transactionOpCheck().forPath(path)
    }

    factory
      .transaction()
      .forOperations(JavaConverters.seqAsJavaList(transactionOps))
      .toScala
      .map(_ => Done)
  }
}
