package mesosphere.marathon
package core.storage.simple

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api._
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConverters

/**
  * A simple Factory that give an instance of the [[CuratorFramework]] provides
  * factory methods to return async builders e.g. [[AsyncCreateBuilder]], [[AsyncDeleteBuilder]] etc.
  *
  * @param curator an instance of the [[CuratorFramework]]
  */
class AsyncCuratorBuilderFactory(curator: CuratorFramework) {

  val async: AsyncCuratorFramework = AsyncCuratorFramework.wrap(curator)

  def isStarted(): Boolean = curator.getState == CuratorFrameworkState.STARTED

  /**
    * Returns create builder. Use to create nodes. By default persistent nodes with empty ACLs are created.
    * Parent nodes will be also created if they don't already exist.
    *
    * @param mode mode to use
    * @param acl ACLs to use
    * @param options Set of [[CreateMode]] option to use.
    * @return
    */
  def create(
    mode: CreateMode = CreateMode.PERSISTENT,
    acl: Seq[ACL] = Seq.empty,
    options: Set[CreateOption] = Set(CreateOption.createParentsIfNeeded)): AsyncCreateBuilder = {

    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.create()
    builder.withOptions(JavaConverters.setAsJavaSet(options), mode)
    if (acl.nonEmpty) builder.withACL(JavaConverters.seqAsJavaList(acl))
    builder
  }

  /**
    * Returns getData builder. Use to read data
    *
    * @param decompressed Cause the data to be de-compressed using the configured compression provider
    * @return
    */
  def getData(decompressed: Boolean = false): AsyncGetDataBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.getData
    if (decompressed) builder.decompressed()
    builder
  }

  /**
    * Returns setData builder. Use to update existing nodes.
    *
    * @param compressed Causes the data to be compressed using the configured compression provider
    * @param version Only sets data if the version matches. By default -1 is used which matches all versions.
    * @return
    */
  def setData(compressed: Boolean = false, version: Int = -1): AsyncSetDataBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.setData()
    if (compressed) builder.compressed()
    if (version != 1) builder.withVersion(version)
    builder
  }

  /**
    * Returns the delete builder. By default the builder has the options set to delete children if they exist and to
    * succeed even if the node doesn't exist ([[org.apache.zookeeper.KeeperException.NoNodeException]] will *not* be thrown)
    *
    * @param options set with [[DeleteOption]] options
    * @param version Only deletes data if the version matches. By default, -1 is used which matches all versions
    * @return
    */
  def delete(options: Set[DeleteOption] = Set(DeleteOption.deletingChildrenIfNeeded, DeleteOption.quietly), version: Int = -1) = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.delete()
    builder.withOptions(JavaConverters.setAsJavaSet(options))
    if (version != -1) builder.withVersion(version)
    builder
  }

  /**
    * Returns the checkExists builder. No options are set by default.
    *
    * @param options set with [[ExistsOption]]
    * @return
    */
  def checkExists(options: Set[ExistsOption] = Set.empty): AsyncExistsBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.checkExists()
    builder.withOptions(JavaConverters.setAsJavaSet(options))
    builder
  }

  /**
    * Returns the children builder.
    *
    * @return
    */
  def children(): AsyncGetChildrenBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    async.getChildren
  }

  /**
    * Returns the sync builder.
    *
    * @return
    */
  def sync(): AsyncSyncBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    async.sync()
  }

  /**
    * Returns the getACL builder.
    *
    * @return
    */
  def getACL(): AsyncGetACLBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    async.getACL()
  }

  /**
    * Returns the setACL builder.
    *
    * @param acl ACLs to set
    * @return
    */
  def setACL(acl: Seq[ACL] = Seq.empty): AsyncSetACLBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.setACL()
    if (acl.nonEmpty) builder.withACL(JavaConverters.seqAsJavaList(acl))
    builder
  }

  /**
    * Returns a transaction builder.
    *
    * @return
    */
  def transaction(): AsyncMultiTransaction = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    async.transaction()
  }

  /**
    * Returns a transactionOp create builder.
    *
    * @param mode node mode
    * @param acl ACL to use
    * @param compressed true if the data should be compressed
    * @return
    */
  def transactionOpCreate(
    mode: CreateMode = CreateMode.PERSISTENT,
    acl: Seq[ACL] = Seq.empty,
    compressed: Boolean = false): AsyncTransactionCreateBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val op = async.transactionOp().create()
    op.withMode(mode)
    if (compressed) op.compressed()
    if (acl.nonEmpty) op.withACL(JavaConverters.seqAsJavaList(acl))
    op
  }

  /**
    * Returns a transactionOp setData builder.
    *
    * @param version node's version to use. By default, -1 is used which matches all versions
    * @param compressed true if the data should be compressed
    * @return
    */
  def transactionOpSetData(
    version: Int = -1,
    compressed: Boolean = false): AsyncTransactionSetDataBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val op = async.transactionOp().setData()
    if (compressed) op.compressed()
    if (version != -1) op.withVersion(version)
    op
  }

  /**
    * Returns a transactionOp delete builder.
    *
    * @param version node's version to use. By default, -1 is used which matches all versions
    * @return
    */
  def transactionOpDelete(version: Int = -1): AsyncTransactionDeleteBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val op = async.transactionOp().delete()
    if (version != -1) op.withVersion(version)
    op
  }

  /**
    * Returns a transactionOp check builder.
    *
    * @param version node's version to use. By default, -1 is used which matches all versions
    * @return
    */
  def transactionOpCheck(version: Int = -1): AsyncTransactionCheckBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val op = async.transactionOp().check()
    if (version != -1) op.withVersion(version)
    op
  }
}

object AsyncCuratorBuilderFactory {
  def apply(curator: CuratorFramework): AsyncCuratorBuilderFactory = new AsyncCuratorBuilderFactory(curator)
}
