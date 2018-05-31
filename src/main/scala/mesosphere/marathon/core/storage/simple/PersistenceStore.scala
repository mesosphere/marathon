package mesosphere.marathon
package core.storage.simple

import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import mesosphere.marathon.core.storage.simple.PersistenceStore.{Node, StoreOp}

import scala.concurrent.Future
import scala.util.Try

/**
  * An interface for a simple persistence store abstraction for the underlying Zookeeper store. Supported are basic
  * create, read, update, delete and check methods along with transaction operations and Zookeeper specific children
  * and sync methods.
  */
trait PersistenceStore {

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
  def create: Flow[Node, String, NotUsed]
  def create(node: Node): Source[String, NotUsed] = Source.single(node).via(create)
  def create(nodes: Seq[Node]): Source[String, NotUsed] = Source.fromIterator(() => nodes.iterator).via(create)

  /**
    * A Flow for reading nodes from the store. It takes a stream of node paths and returns a stream of Try[Node] elements.
    * It's a Success[Node] for existing nodes or Failure(e) for non-existing nodes where e is an instance of
    * [[org.apache.zookeeper.KeeperException.NoNodeException]]. The exception contains the path to the node which
    * simplifies handling failed reads. For other exceptions stream is completed with a failure.
    *
    * @return
    */
  def read: Flow[String, Try[Node], NotUsed]
  def read(path: String): Source[Try[Node], NotUsed] = Source.single(path).via(read)
  def read(paths: Seq[String]): Source[Try[Node], NotUsed] = Source.fromIterator(() => paths.iterator).via(read)

  /**
    * A Flow for updating nodes in the store. It takes a stream of nodes and returns a stream of paths to indicate
    * a successful update operation for the returned path. Only existing nodes can be updated. If a node with the path
    * does not exist a [[org.apache.zookeeper.KeeperException.NoNodeException]] is thrown.
    *
    * @return
    */
  def update: Flow[Node, String, NotUsed]
  def update(node: Node): Source[String, NotUsed] = Source.single(node).via(update)
  def update(nodes: Seq[Node]): Source[String, NotUsed] = Source.fromIterator(() => nodes.iterator).via(update)

  /**
    * A Flow for deleting nodes from the repository. It takes a stream of paths and returns a stream of paths to indicate
    * a successful deletion operation for the returned path. If the node doesn't exist the operation is still considered
    * successful.
    *
    * @return
    */
  def delete: Flow[String, String, NotUsed]
  def delete(path: String): Source[String, NotUsed] = Source.single(path).via(delete)
  def delete(paths: Seq[String]): Source[String, NotUsed] = Source.fromIterator(() => paths.iterator).via(delete)

  /**
    * Returns the list of paths for children nodes for the passed path. Note that returned path is relative and does
    * not contain node's path prefix.
    *
    * @param path parent node's path
    * @return
    */
  def children(path: String): Future[Seq[String]]

  /**
    * Checks for the existence of a node with passed path.
    *
    * @param path node's path
    * @return
    */
  def exists(path: String): Future[Boolean]

  /**
    * Method syncs state with underlying store.
    *
    * @param path node path to sync
    * @return
    */
  def sync(path: String): Future[Done]

  /**
    * Method takes a list of transaction [[mesosphere.marathon.core.storage.simple.PersistenceStore.StoreOp]] operations
    * and submits them. An exception is thrown if one of the operations fail. Currently only create, update, delete and
    * check operations are supported.
    *
    * Note: Due to current state of the underlying Curator API, [[mesosphere.marathon.core.storage.simple.PersistenceStore.CreateOp]]s
    * can't create parent nodes for nested paths if parent nodes does not exist.
    *
    * @param operations a list of transaction operations
    * @return
    */
  def transaction(operations: Seq[StoreOp]): Future[Done]
}

object PersistenceStore {
  /**
    * Main storage unit: it's a key-value like structure, where the key is the path
    * to the node e.g. `/app1` which can also be nested e.g. `/apps/app1` and the data
    * is the value (represented as ByteString)
    *
    * @param path node's path
    * @param data node's payload
    */
  case class Node(path: String, data: ByteString)

  /**
    * Helper wrappers around transaction operations.
    */
  sealed trait StoreOp
  case class CreateOp(node: Node) extends StoreOp
  case class UpdateOp(node: Node) extends StoreOp
  case class DeleteOp(path: String) extends StoreOp
  case class CheckOp(path: String) extends StoreOp
}