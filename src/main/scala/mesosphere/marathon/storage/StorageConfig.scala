package mesosphere.marathon
package storage

import java.net.URI

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.Materializer
import mesosphere.marathon.core.base.{CrashStrategy, LifecycleState}
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore}
import mesosphere.marathon.core.storage.store.impl.memory.{Identity, InMemoryPersistenceStore, RamId}
import mesosphere.marathon.core.storage.store.impl.zk.{RichCuratorFramework, ZkId, ZkPersistenceStore, ZkSerialized}
import mesosphere.marathon.metrics.Metrics

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

  protected def leafStore(metrics: Metrics)(
    implicit
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): BasePersistenceStore[K, C, S]

  protected def lazyStore(metrics: Metrics)(
    implicit
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): PersistenceStore[K, C, S] = {
    val lazyCachingStore: PersistenceStore[K, C, S] = LazyCachingPersistenceStore(metrics, leafStore(metrics))
    versionCacheConfig.fold(lazyCachingStore){ config => LazyVersionCachingPersistentStore(metrics, lazyCachingStore, config) }
  }

  def store(metrics: Metrics)(implicit
    mat: Materializer,
    ctx: ExecutionContext, scheduler: Scheduler, actorRefFactory: ActorSystem): PersistenceStore[K, C, S] = {
    cacheType match {
      case NoCaching => leafStore(metrics)
      case LazyCaching => lazyStore(metrics)
      case EagerCaching => new LoadTimeCachingPersistenceStore[K, C, S](leafStore(metrics))
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
    client: RichCuratorFramework,
    maxConcurrent: Int,
    maxOutstanding: Int,
    maxVersions: Int,
    storageCompactionScanBatchSize: Int,
    storageCompactionInterval: FiniteDuration,
    groupVersionsCacheSize: Int,
    versionCacheConfig: Option[VersionCacheConfig],
    availableFeatures: Set[String],
    defaultNetworkName: Option[String],
    backupLocation: Option[URI]
) extends PersistenceStorageConfig[ZkId, String, ZkSerialized] {

  def leafStore(metrics: Metrics)(
    implicit
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): BasePersistenceStore[ZkId, String, ZkSerialized] = {

    actorSystem.registerOnTermination {
      client.close()
    }
    new ZkPersistenceStore(metrics, client, maxConcurrent, maxOutstanding)
  }

}

object CuratorZk {
  val StoreName = "zk"
  def apply(conf: StorageConf, curatorFramework: RichCuratorFramework): CuratorZk =
    CuratorZk(
      cacheType = if (conf.storeCache()) LazyCaching else NoCaching,
      curatorFramework,
      maxConcurrent = conf.zkMaxConcurrency(),
      maxOutstanding = Int.MaxValue,
      maxVersions = conf.maxVersions(),
      storageCompactionInterval = conf.storageCompactionInterval().seconds,
      storageCompactionScanBatchSize = conf.storageCompactionScanBatchSize(),
      groupVersionsCacheSize = conf.groupVersionsCacheSize(),
      versionCacheConfig = if (conf.versionCacheEnabled()) StorageConfig.DefaultVersionCacheConfig else None,
      availableFeatures = conf.availableFeatures,
      backupLocation = conf.backupLocation.toOption,
      defaultNetworkName = conf.defaultNetworkName.toOption
    )
}

case class InMem(
    maxVersions: Int,
    storageCompactionScanBatchSize: Int,
    availableFeatures: Set[String],
    defaultNetworkName: Option[String],
    backupLocation: Option[URI],
    groupVersionsCacheSize: Int
) extends PersistenceStorageConfig[RamId, String, Identity] {
  override val cacheType: CacheType = NoCaching
  override val versionCacheConfig: Option[VersionCacheConfig] = None

  protected def leafStore(metrics: Metrics)(
    implicit
    mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorSystem: ActorSystem): BasePersistenceStore[RamId, String, Identity] =
    new InMemoryPersistenceStore(metrics)
}

object InMem {
  val StoreName = "mem"

  def apply(conf: StorageConf): InMem =
    InMem(conf.maxVersions(), conf.storageCompactionScanBatchSize(), conf.availableFeatures, conf.defaultNetworkName.toOption, conf.backupLocation.toOption, conf.groupVersionsCacheSize())
}

object StorageConfig {
  val DefaultVersionCacheConfig = Option(VersionCacheConfig.Default)

  def apply(conf: StorageConf, curatorFramework: RichCuratorFramework): StorageConfig = {
    conf.internalStoreBackend() match {
      case InMem.StoreName => InMem(conf)
      case CuratorZk.StoreName => CuratorZk(conf, curatorFramework)
    }
  }

  /**
    * Return an optional curator framework instance, depending on the underlying storage type. For [[InMem]] storage None
    * is returned. For [[CuratorZk]] [[RichCuratorFramework]] instance is initialized and connection to Zookeeper is established.
    *
    * @param conf
    * @param crashStrategy
    * @param lifecycleState
    * @return
    */
  def curatorFramework(conf: ZookeeperConf, crashStrategy: CrashStrategy, lifecycleState: LifecycleState): RichCuratorFramework = {
    val client = RichCuratorFramework(conf, crashStrategy)
    client.start()
    client.blockUntilConnected(lifecycleState, crashStrategy)
    // make sure that we read up-to-date values from ZooKeeper
    Await.ready(client.sync("/"), Duration.Inf)

    client
  }
}
