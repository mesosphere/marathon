package mesosphere.marathon
package storage

import java.net.URI
import java.util
import java.util.Collections

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.Materializer
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore}
import mesosphere.marathon.core.storage.store.impl.memory.{Identity, InMemoryPersistenceStore, RamId}
import mesosphere.marathon.core.storage.store.impl.zk.{RichCuratorFramework, ZkId, ZkPersistenceStore, ZkSerialized}
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.GzipCompressionProvider
import org.apache.curator.framework.{AuthInfo, CuratorFrameworkFactory}
import org.apache.curator.retry.BoundedExponentialBackoffRetry
import org.apache.zookeeper.data.ACL

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

sealed trait StorageConfig extends Product with Serializable {
  def backupLocation: Option[URI]
}

sealed trait CacheType
case object NoCaching extends CacheType
case object EagerCaching extends CacheType
case object LazyCaching extends CacheType

object CacheType {
  def apply(str: String): CacheType = str.toLowerCase match {
    case str: String if str.startsWith("eager") => EagerCaching
    case str: String if str.startsWith("lazy") => LazyCaching
    case _ => NoCaching
  }
}

sealed trait PersistenceStorageConfig[K, C, S] extends StorageConfig {
  val maxVersions: Int
  val cacheType: CacheType
  val versionCacheConfig: Option[VersionCacheConfig]

  protected def leafStore(implicit mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): BasePersistenceStore[K, C, S]

  protected def lazyStore(implicit mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): PersistenceStore[K, C, S] = {
    val lazyCachingStore: PersistenceStore[K, C, S] = LazyCachingPersistenceStore(leafStore)
    versionCacheConfig.fold(lazyCachingStore){ config => LazyVersionCachingPersistentStore(lazyCachingStore, config) }
  }

  def store(implicit
    mat: Materializer,
    ctx: ExecutionContext, scheduler: Scheduler, actorRefFactory: ActorSystem): PersistenceStore[K, C, S] = {
    cacheType match {
      case NoCaching => leafStore
      case LazyCaching => lazyStore
      case EagerCaching => new LoadTimeCachingPersistenceStore[K, C, S](leafStore)
    }
  }
}

case class VersionCacheConfig(
    maxEntries: Int,
    purgeCount: Int,
    pRemove: Double
)

object VersionCacheConfig {
  /**
    * max number of entries allowed in the versioned value cache before entries are purged
    */
  protected val MaxVersionedCacheSize = 10000
  /**
    * probability that, during a purge of the versioned value cache, a given entry will be removed
    */
  protected val ProbabilityToRemoveFromCache = 0.05

  val Default = apply(MaxVersionedCacheSize, ProbabilityToRemoveFromCache)

  def apply(maxEntries: Int, pRemove: Double): VersionCacheConfig =
    new VersionCacheConfig(maxEntries, (maxEntries * pRemove).toInt, pRemove)
}

case class CuratorZk(
    cacheType: CacheType,
    sessionTimeout: Option[Duration],
    connectionTimeout: Option[Duration],
    timeout: Duration,
    zkHosts: String,
    zkPath: String,
    zkAcls: util.List[ACL],
    username: Option[String],
    password: Option[String],
    enableCompression: Boolean,
    retryPolicy: RetryPolicy,
    maxConcurrent: Int,
    maxOutstanding: Int,
    maxVersions: Int,
    storageCompactionScanBatchSize: Int,
    storageCompactionInterval: FiniteDuration,
    groupVersionsCacheSize: Int,
    versionCacheConfig: Option[VersionCacheConfig],
    availableFeatures: Set[String],
    lifecycleState: LifecycleState,
    defaultNetworkName: Option[String],
    backupLocation: Option[URI]
) extends PersistenceStorageConfig[ZkId, String, ZkSerialized] {

  lazy val client: RichCuratorFramework = {
    val builder = CuratorFrameworkFactory.builder()
    builder.connectString(zkHosts)
    sessionTimeout.foreach(t => builder.sessionTimeoutMs(t.toMillis.toInt))
    connectionTimeout.foreach(t => builder.connectionTimeoutMs(t.toMillis.toInt))
    if (enableCompression) builder.compressionProvider(new GzipCompressionProvider)
    (username, password) match {
      case (Some(user), Some(pass)) =>
        builder.authorization(Collections.singletonList(new AuthInfo("digest", s"$user:$pass".getBytes("UTF-8"))))
      case _ =>
    }
    builder.aclProvider(new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = zkAcls

      override def getAclForPath(path: String): util.List[ACL] = zkAcls
    })
    builder.retryPolicy(retryPolicy)
    builder.namespace(zkPath.stripPrefix("/"))
    val client = RichCuratorFramework(builder.build())
    client.start()
    client.blockUntilConnected(lifecycleState)

    // make sure that we read up-to-date values from ZooKeeper
    Await.ready(client.sync("/"), Duration.Inf)

    client
  }

  def leafStore(implicit mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): BasePersistenceStore[ZkId, String, ZkSerialized] = {

    actorSystem.registerOnTermination {
      client.close()
    }
    new ZkPersistenceStore(client, timeout, maxConcurrent, maxOutstanding)
  }

}

object CuratorZk {
  val StoreName = "zk"
  def apply(conf: StorageConf, lifecycleState: LifecycleState): CuratorZk =
    CuratorZk(
      cacheType = if (conf.storeCache()) LazyCaching else NoCaching,
      sessionTimeout = Some(conf.zkSessionTimeoutDuration),
      connectionTimeout = Some(conf.zkConnectionTimeoutDuration),
      timeout = conf.zkTimeoutDuration,
      zkHosts = conf.zkHosts,
      zkPath = conf.zooKeeperStatePath,
      zkAcls = conf.zkDefaultCreationACL,
      username = conf.zkUsername,
      password = conf.zkPassword,
      enableCompression = conf.zooKeeperCompressionEnabled(),
      retryPolicy = new BoundedExponentialBackoffRetry(conf.zooKeeperOperationBaseRetrySleepMs(), conf.zooKeeperTimeout().toInt, conf.zooKeeperOperationMaxRetries()),
      maxConcurrent = conf.zkMaxConcurrency(),
      maxOutstanding = Int.MaxValue,
      maxVersions = conf.maxVersions(),
      storageCompactionInterval = conf.storageCompactionInterval().seconds,
      storageCompactionScanBatchSize = conf.storageCompactionScanBatchSize(),
      groupVersionsCacheSize = conf.groupVersionsCacheSize(),
      versionCacheConfig = if (conf.versionCacheEnabled()) StorageConfig.DefaultVersionCacheConfig else None,
      availableFeatures = conf.availableFeatures,
      backupLocation = conf.backupLocation.toOption,
      lifecycleState = lifecycleState,
      defaultNetworkName = conf.defaultNetworkName.toOption
    )
}

case class InMem(
    maxVersions: Int,
    storageCompactionScanBatchSize: Int,
    availableFeatures: Set[String],
    defaultNetworkName: Option[String],
    backupLocation: Option[URI]
) extends PersistenceStorageConfig[RamId, String, Identity] {
  override val cacheType: CacheType = NoCaching
  override val versionCacheConfig: Option[VersionCacheConfig] = None

  protected def leafStore(implicit mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): BasePersistenceStore[RamId, String, Identity] =
    new InMemoryPersistenceStore()
}

object InMem {
  val StoreName = "mem"

  def apply(conf: StorageConf): InMem =
    InMem(conf.maxVersions(), conf.storageCompactionScanBatchSize(), conf.availableFeatures, conf.defaultNetworkName.toOption, conf.backupLocation.toOption)
}

object StorageConfig {
  val DefaultVersionCacheConfig = Option(VersionCacheConfig.Default)

  def apply(conf: StorageConf, lifecycleState: LifecycleState): StorageConfig = {
    conf.internalStoreBackend() match {
      case InMem.StoreName => InMem(conf)
      case CuratorZk.StoreName => CuratorZk(conf, lifecycleState)
    }
  }
}
