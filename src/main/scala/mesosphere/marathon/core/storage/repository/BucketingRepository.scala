package mesosphere.marathon
package core.storage.repository

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore

import scala.concurrent.Future
import scala.util.Try

/**
  * Fun fact about Zookeeper: you can create a lot of children nodes but if you try to get all of them by calling
  * [[mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore.children()]] (on the parent node) you are likely
  * to get an error like:
  * ```
  * java.io.IOException: Packet len20800020 is out of range!
  *  at org.apache.zookeeper.ClientCnxnSocket.readLength(ClientCnxnSocket.java:112)
  *  ...
  * ```
  *
  * Turns out that ZK has a packet length limit which is 4096 * 1024 bytes by default:
  * https://github.com/apache/zookeeper/blob/0cb4011dac7ec28637426cafd98b4f8f299ef61d/src/java/main/org/apache/zookeeper/client/ZKClientConfig.java#L58
  *
  * It can be altered by setting `jute.maxbuffer` environment variable. Packet in this context is an application level
  * packet containing all the children. Some experimentation showed that each child element in that packet has ~4bytes
  * overhead for encoding. So, let's say each child node e.g. holding Marathon app definition is 50 characters long (
  * seems like a good guess given 3-4 levels of nesting e.g. `eng_dev_databases_my-favourite-mysql-instance`), we could only
  * have: 4096 * 1024 / (50 + 4) =  ~75k children nodes until we hit the exception.
  *
  * This trait solves the above mentioned limitations by putting the elements into buckets. Each element is then
  * stored in `/$basePath/$bucket/$relative/element/path`. This way the total number of possible elements is
  * NumberOfBuckets * MaxElementsPerBucket where later parameter depends on the length of children node name (see
  * calculation above)
  * Every element passed should implement [[BucketNode]] trait that defines the hash of the bucket where the element is stored
  * and the relative path below.
  *
  * Same goes for reading elements - one need to pass a [[BucketPath]] which defines bucket hash along with the element
  * relative path.
  */
trait BucketingRepository {

  def store: ZooKeeperPersistenceStore

  /**
    * Max buckets number should never be changed in production since all data location will change and
    * re-hashing will be needed
    */
  val MaxBuckets = 16

  /**
    * Map of bucket numbers -> bucket name e.g. (5 -> "05")
    */
  val buckets: Map[Int, String] = (0 to MaxBuckets).map(b => b -> f"$b%02d")(collection.breakOut)

  /**
    * Base path to all stored elements e.g. /templates
    */
  val base: String

  /**
    * Return bucket name calculated from the passed bucket hash.
    */
  def bucket(bucketNode: BucketNode): String = bucket(bucketNode.bucketPath)
  def bucket(bucketPath: BucketPath): String = buckets(bucketPath.bucketHash % buckets.size)

  /**
    * Return full path for the passed [[BucketNode]]/[[BucketPath]] combining base path, bucket name and relative path.
    */
  def path(bucketNode: BucketNode): String = path(bucketNode.bucketPath)
  def path(bucketPath: BucketPath): String = s"$base/${bucket(bucketPath)}/${bucketPath.relativePath}"

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

  def children(bucketPath: BucketPath): Future[Seq[String]] = store.children(path(bucketPath))
  def children(bucketPaths: Seq[BucketPath]): Source[Seq[String], NotUsed] = Source(bucketPaths).via(childrenFlow)
  def childrenFlow: Flow[BucketPath, Seq[String], NotUsed] =
    Flow[BucketPath]
      .via(toPath)
      .via(store.childrenFlow)

  def exists(bucketPath: BucketPath): Future[Boolean] = store.exists(path(bucketPath))
  def exists(bucketPaths: Seq[BucketPath]): Source[Boolean, NotUsed] = Source(bucketPaths).via(existsFlow)
  def existsFlow: Flow[BucketPath, Boolean, NotUsed] =
    Flow[BucketPath]
      .via(toPath)
      .via(store.existsFlow)
}

/**
  * To use the [[BucketingRepository]] elements should implement this trait that defines the hash of the bucket where
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
  * Used in [[BucketingRepository]] when reading bucketed elements. Bucket path is defined by the bucket hash and a
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