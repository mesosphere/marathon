package mesosphere.marathon
package experimental.storage

import java.nio.file.Paths

import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.{CheckOp, CreateOp, DeleteOp, Node, UpdateOp}
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.experimental.storage.BucketingPersistenceStore.{BucketedCheckOp, BucketedCreateOp, BucketedDeleteOp, BucketedUpdateOp, BucketingStoreOp}

import scala.concurrent.Future
import scala.util.Try

/**
  * This is an experimental extension to the [[ZooKeeperPersistenceStore]]. It allows users to store a lot of non-hierarchical
  * entries in the Zookeeper and at the same time avoiding the problem of having too many children in one parent znode (see
  * next paragraph) by storing entries in the buckets.
  *
  * An interesting fact about Zookeeper: one can create a lot znodes in one parent znode but if you try to get all of them by calling
  * [[mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore.children()]] (on the parent znode) you are likely
  * to get an error like:
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
  * This trait solves the above mentioned limitations by putting the elements into buckets. Each element is then
  * stored in `/$basePath/$bucket/$relative/element/path`. This way the total number of possible elements is
  * NumberOfBuckets * MaxElementsPerBucket where later parameter depends on the length of children znode name (see
  * calculation above)
  * Every element passed should implement [[BucketNode]] trait that defines the hash of the bucket where the element is stored
  * and the relative path below.
  *
  * Same goes for reading elements - one need to pass a [[BucketPath]] which defines bucket hash along with the element
  * relative path.
  *
  * Note: this class isn't used anywhere yet and serves more as a demonstration.
  *
  * @param base base path to all stored elements e.g. "/templates"
  * @param store instance of the underlying [[ZooKeeperPersistenceStore]]
  * @param MaxBuckets number of buckets to use. It hould never be changed in production since all data location will
  *                   change and re-hashing will be needed
  */
class BucketingPersistenceStore(store: ZooKeeperPersistenceStore, base: String, MaxBuckets: Int = 16) {

  /**
    * Map of bucket numbers -> bucket name e.g. (5 -> "05")
    */
  def buckets: Map[Int, String] = (0 to MaxBuckets).map(b => b -> f"$b%02d")(collection.breakOut)

  /**
    * Return bucket name calculated from the passed bucket hash.
    */
  def bucket(bucketNode: BucketNode): String = bucket(bucketNode.bucketPath)
  def bucket(bucketPath: BucketPath): String = buckets(bucketPath.bucketHash % buckets.size)

  /**
    * Return full path for the passed [[BucketNode]]/[[BucketPath]] combining base path, bucket name and relative path.
    */
  def path(bucketNode: BucketNode): String = path(bucketNode.bucketPath)
  def path(bucketPath: BucketPath): String = Paths.get(base, bucket(bucketPath), bucketPath.relativePath).toString

  /**
    * Helper stages, mapping from BucketNode/BucketPath to Node/Path
    */
  def toNode: Flow[BucketNode, Node, NotUsed] = Flow[BucketNode].map(bn => Node(path(bn), bn.payload))
  def toPath: Flow[BucketPath, String, NotUsed] = Flow[BucketPath].map(bp => path(bp))

  // All methods below wrap the storage methods by mapping from BucketNode/BucketPath to Node/Path
  def create(bucketNode: BucketNode): Future[String] = store.create(Node(path(bucketNode), bucketNode.payload))
  def create(bucketNodes: Seq[BucketNode]): Source[String, NotUsed] = Source(bucketNodes).via(createFlow)
  def createFlow: Flow[BucketNode, String, NotUsed] =
    Flow[BucketNode]
      .via(toNode)
      .via(store.createFlow)

  def read(bucketPath: BucketPath): Future[Try[Node]] = store.read(path(bucketPath))
  def read(bucketPaths: Seq[BucketPath]): Source[Try[Node], NotUsed] = Source(bucketPaths).via(readFlow)
  def readFlow: Flow[BucketPath, Try[Node], NotUsed] =
    Flow[BucketPath]
      .via(toPath)
      .via(store.readFlow)

  def update(bucketNode: BucketNode): Future[String] = store.update(Node(path(bucketNode), bucketNode.payload))
  def update(bucketNodes: Seq[BucketNode]): Source[String, NotUsed] = Source(bucketNodes).via(updateFlow)
  def updateFlow: Flow[BucketNode, String, NotUsed] =
    Flow[BucketNode]
      .via(toNode)
      .via(store.updateFlow)

  def delete(bucketPath: BucketPath): Future[String] = store.delete(path(bucketPath))
  def delete(bucketPaths: Seq[BucketPath]): Source[String, NotUsed] = Source(bucketPaths).via(deleteFlow)
  def deleteFlow: Flow[BucketPath, String, NotUsed] =
    Flow[BucketPath]
      .via(toPath)
      .via(store.deleteFlow)

  def children(bucketPath: BucketPath): Future[Seq[String]] = store.children(path(bucketPath), true)
  def children(bucketPaths: Seq[BucketPath]): Source[Seq[String], NotUsed] = Source(bucketPaths).via(childrenFlow)
  def childrenFlow: Flow[BucketPath, Seq[String], NotUsed] =
    Flow[BucketPath]
      .via(toPath)
      .via(store.childrenFlow(true))

  def exists(bucketPath: BucketPath): Future[Boolean] = store.exists(path(bucketPath))
  def exists(bucketPaths: Seq[BucketPath]): Source[Boolean, NotUsed] = Source(bucketPaths).via(existsFlow)
  def existsFlow: Flow[BucketPath, Boolean, NotUsed] =
    Flow[BucketPath]
      .via(toPath)
      .via(store.existsFlow)

  def transaction(operations: Seq[BucketingStoreOp]): Future[Done] = store.transaction(operations.map{
    case BucketedCreateOp(bn) => CreateOp(node = Node(path(bn), bn.payload))
    case BucketedUpdateOp(bn) => UpdateOp(node = Node(path(bn), bn.payload))
    case BucketedDeleteOp(bp) => DeleteOp(path = path(bp))
    case BucketedCheckOp(bp) => CheckOp(path = path(bp))
  })
}

object BucketingPersistenceStore {
  sealed trait BucketingStoreOp
  case class BucketedCreateOp(bn: BucketNode) extends BucketingStoreOp
  case class BucketedUpdateOp(bn: BucketNode) extends BucketingStoreOp
  case class BucketedDeleteOp(bp: BucketPath) extends BucketingStoreOp
  case class BucketedCheckOp(bp: BucketPath) extends BucketingStoreOp
}

/**
  * To use the [[BucketingPersistenceStore]] elements should implement this trait that defines the hash of the bucket where
  * the element is stored and the relative path below. Bucket hash should be stable and unambiguous so that semantically
  * same elements always return the same hash. [[scala.util.hashing.MurmurHash3]] can be useful in such situations.
  */
trait BucketNode {

  /**
    * Return bucket path of the element.
    * @return
    */
  def bucketPath: BucketPath

  /**
    * Return node data.
    * @return
    */
  def payload: ByteString
}

/**
  * Used in [[BucketingPersistenceStore]] when reading bucketed elements. Bucket path is defined by the bucket hash and a
  * relative path (below the bucket).
  */
trait BucketPath {

  /**
    * Node hash which defines the corresponding bucket. The hash should be stable for the same data to land in the
    * same bucket.
    *
    * @return
    */
  def bucketHash: Int

  /**
    * Relative path for the bucketed element. It should not contain the starting `/` e.g. `bar/foo`
    */
  def relativePath: String
}