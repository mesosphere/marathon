package mesosphere.marathon
package core.storage.zookeeper

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.x.async.AsyncCuratorFramework
import org.apache.curator.x.async.api._
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConverters

/**
  * Simple case class holding factory's configuration options. Sensible defaults are provided for all options.
  *
  * @param createOptions a set with create options. By default node parents are created if needed and node data is compressed
  * @param deleteOptions a set with delete options. By default node children are deleted if the node doesn't exist the delete method will appear to succeed
  * @param existsOptions a set with exists options. By default no options are set
  * @param createMode node create mode. By default [[CreateMode.PERSISTENT]] nodes are created
  * @param acl node ACL. By default an empty list is used
  * @param nodeVersion node version used to update node data or delete it. By default, -1 is used which matches all versions
  * @param compressedData true if node data compression is enabled. Compression is enabled by default
  */
case class AsyncCuratorBuilderSettings(
    createOptions: Set[CreateOption] = Set(CreateOption.createParentsIfNeeded, CreateOption.compress),
    deleteOptions: Set[DeleteOption] = Set(DeleteOption.deletingChildrenIfNeeded, DeleteOption.quietly),
    existsOptions: Set[ExistsOption] = Set.empty,
    createMode: CreateMode = CreateMode.PERSISTENT,
    acl: Seq[ACL] = Seq.empty,
    nodeVersion: Int = -1,
    compressedData: Boolean = true)

/**
  * A simple Factory that give an instance of the [[CuratorFramework]] provides
  * factory methods to return async builders e.g. [[AsyncCreateBuilder]], [[AsyncDeleteBuilder]] etc.
  *
  * @param curator an instance of the [[CuratorFramework]]
  * @param defaults async builder settings which are used as default parameters which simplifies request building. However
  *             each factory method also allows overriding the defaults for every call.
  */
class AsyncCuratorBuilderFactory(curator: CuratorFramework, defaults: AsyncCuratorBuilderSettings) {

  private val async: AsyncCuratorFramework = AsyncCuratorFramework.wrap(curator)

  def isStarted(): Boolean = curator.getState == CuratorFrameworkState.STARTED

  /**
    * Returns create builder. Use to create nodes. By default persistent created nodes are compressed and have empty ACLs.
    * Parent nodes will be also created if they don't already exist.
    *
    * @param mode mode to use
    * @param acl ACLs to use
    * @param options Set of [[CreateMode]] option to use.
    * @return
    */
  def create(
    mode: CreateMode = defaults.createMode,
    acl: Seq[ACL] = defaults.acl,
    options: Set[CreateOption] = defaults.createOptions): AsyncCreateBuilder = {

    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val builder = async.create()
    builder.withOptions(JavaConverters.setAsJavaSet(options), mode)
    if (acl.nonEmpty) builder.withACL(JavaConverters.seqAsJavaList(acl))
    builder
  }

  /**
    * Returns getData builder. Use to read data. By default nodes are assumed to be compressed.
    *
    * @param decompressed Cause the data to be de-compressed using the configured compression provider
    * @return
    */
  def getData(decompressed: Boolean = defaults.compressedData): AsyncGetDataBuilder = {
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
  def setData(compressed: Boolean = defaults.compressedData, version: Int = defaults.nodeVersion): AsyncSetDataBuilder = {
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
  def delete(options: Set[DeleteOption] = defaults.deleteOptions, version: Int = defaults.nodeVersion) = {
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
  def checkExists(options: Set[ExistsOption] = defaults.existsOptions): AsyncExistsBuilder = {
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
  def setACL(acl: Seq[ACL] = defaults.acl): AsyncSetACLBuilder = {
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
    mode: CreateMode = defaults.createMode,
    acl: Seq[ACL] = defaults.acl,
    compressed: Boolean = defaults.compressedData): AsyncTransactionCreateBuilder = {
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
    version: Int = defaults.nodeVersion,
    compressed: Boolean = defaults.compressedData): AsyncTransactionSetDataBuilder = {
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
  def transactionOpDelete(version: Int = defaults.nodeVersion): AsyncTransactionDeleteBuilder = {
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
  def transactionOpCheck(version: Int = defaults.nodeVersion): AsyncTransactionCheckBuilder = {
    assert(isStarted(), "Curator connection to ZK has been closed/not started yet")

    val op = async.transactionOp().check()
    if (version != -1) op.withVersion(version)
    op
  }
}

object AsyncCuratorBuilderFactory {
  def apply(
    curator: CuratorFramework,
    defaults: AsyncCuratorBuilderSettings = new AsyncCuratorBuilderSettings()): AsyncCuratorBuilderFactory = {
    assert(
      defaults.createOptions.contains(CreateOption.compress) == defaults.compressedData,
      "Data compression should be enabled/disabled in createOptions and dataCompressed flag")
    new AsyncCuratorBuilderFactory(curator, defaults)
  }
}
