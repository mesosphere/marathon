package mesosphere.marathon
package core.storage.zookeeper

import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.{Node, StoreOp}

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
    */
  def createFlow: Flow[Node, String, NotUsed]
  def create(node: Node): Future[String]
  def create(nodes: Seq[Node]): Source[String, NotUsed] = Source(nodes).via(createFlow)

  /**
    * A Flow for reading nodes from the store. It takes a stream of node paths and returns a stream of Try[Node] elements.
    */
  def readFlow: Flow[String, Try[Node], NotUsed]
  def read(path: String): Future[Try[Node]]
  def read(paths: Seq[String]): Source[Try[Node], NotUsed] = Source(paths).via(readFlow)

  /**
    * A Flow for updating nodes in the store. It takes a stream of nodes and returns a stream of paths to indicate
    * a successful update operation for the returned path.
    */
  def updateFlow: Flow[Node, String, NotUsed]
  def update(node: Node): Future[String]
  def update(nodes: Seq[Node]): Source[String, NotUsed] = Source(nodes).via(updateFlow)

  /**
    * A Flow for deleting nodes from the repository. It takes a stream of paths and returns a stream of paths to indicate
    * a successful deletion operation for the returned path.
    */
  def deleteFlow: Flow[String, String, NotUsed]
  def delete(path: String): Future[String]
  def delete(paths: Seq[String]): Source[String, NotUsed] = Source(paths).via(deleteFlow)

  /**
    * Returns the list of paths for children nodes for the passed path. Note that returned path is absolute and contains
    * node's path prefix.
    *
    * @return
    */
  def childrenFlow(absolute: Boolean): Flow[String, Seq[String], NotUsed]
  def children(path: String, absolute: Boolean): Future[Seq[String]]
  def children(paths: Seq[String], absolute: Boolean): Source[Seq[String], NotUsed] = Source(paths).via(childrenFlow(absolute))

  /**
    * Checks for the existence of a node with passed path.
    *
    * @return
    */
  def existsFlow: Flow[String, Boolean, NotUsed]
  def exists(path: String): Future[Boolean]
  def exists(paths: Seq[String]): Source[Boolean, NotUsed] = Source(paths).via(existsFlow)

  /**
    * Method syncs state with underlying store.
    *
    * @param path node path to sync
    * @return
    */
  def sync(path: String): Future[Done]

  /**
    * Method takes a list of transaction [[mesosphere.marathon.core.storage.zookeeper.PersistenceStore.StoreOp]] operations
    * and submits them. An exception is thrown if one of the operations fail.
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
