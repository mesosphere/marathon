package mesosphere.marathon
package storage

import java.net.URI
import java.util
import java.util.Collections

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.Config
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryPersistenceStore, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ NoRetryPolicy, RichCuratorFramework, ZkId, ZkPersistenceStore, ZkSerialized }
import mesosphere.marathon.util.{ RetryConfig, toRichConfig }
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.GzipCompressionProvider
import org.apache.curator.framework.{ AuthInfo, CuratorFrameworkFactory }
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL

import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

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
    retryConfig: RetryConfig,
    maxConcurrent: Int,
    maxOutstanding: Int,
    maxVersions: Int,
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
    builder.retryPolicy(NoRetryPolicy) // We use our own Retry.
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
      retryConfig = RetryConfig(),
      maxConcurrent = conf.zkMaxConcurrency(),
      maxOutstanding = Int.MaxValue,
      maxVersions = conf.maxVersions(),
      versionCacheConfig = if (conf.versionCacheEnabled()) StorageConfig.DefaultVersionCacheConfig else None,
      availableFeatures = conf.availableFeatures,
      backupLocation = conf.backupLocation.get,
      lifecycleState = lifecycleState,
      defaultNetworkName = conf.defaultNetworkName.get
    )

  def apply(config: Config, lifecycleState: LifecycleState): CuratorZk = {
    val username = config.optionalString("username")
    val password = config.optionalString("password")
    val acls = (username, password) match {
      case (Some(_), Some(_)) => ZooDefs.Ids.CREATOR_ALL_ACL
      case _ => ZooDefs.Ids.OPEN_ACL_UNSAFE
    }
    CuratorZk(
      cacheType = CacheType(config.string("cache-type", "lazy")),
      sessionTimeout = config.optionalDuration("session-timeout"),
      connectionTimeout = config.optionalDuration("connection-timeout"),
      timeout = config.duration("timeout", 10.seconds),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = s"${config.string("path", "marathon")}/state",
      zkAcls = acls,
      username = username,
      password = password,
      enableCompression = config.bool("enable-compression", true),
      retryConfig = RetryConfig(config),
      maxConcurrent = config.int("max-concurrent-requests", 32),
      maxOutstanding = config.int("max-concurrent-outstanding", Int.MaxValue),
      maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions),
      versionCacheConfig =
        if (config.bool("version-cache-enabled", true)) StorageConfig.DefaultVersionCacheConfig else None,
      availableFeatures = config.stringList("available-features", Seq.empty).to[Set],
      lifecycleState = lifecycleState,
      defaultNetworkName = config.optionalString("default-network-name"),
      backupLocation = config.optionalString("backup-location").map(new URI(_))
    )
  }
}

case class InMem(
    maxVersions: Int,
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
    InMem(conf.maxVersions(), conf.availableFeatures, conf.defaultNetworkName.get, conf.backupLocation.get)

  def apply(conf: Config): InMem =
    InMem(
      conf.int("max-versions", StorageConfig.DefaultMaxVersions),
      availableFeatures = conf.stringList("available-features", Seq.empty).to[Set],
      defaultNetworkName = conf.optionalString("default-network-name"),
      backupLocation = conf.optionalString("backup-location").map(new URI(_))
    )
}

object StorageConfig {
  val DefaultVersionCacheConfig = Option(VersionCacheConfig.Default)

  val DefaultLegacyMaxVersions = 25
  val DefaultMaxVersions = 5000
  def apply(conf: StorageConf, lifecycleState: LifecycleState): StorageConfig = {
    conf.internalStoreBackend() match {
      case InMem.StoreName => InMem(conf)
      case CuratorZk.StoreName => CuratorZk(conf, lifecycleState)
    }
  }

  def apply(conf: Config, lifecycleState: LifecycleState): StorageConfig = {
    conf.string("storage-type", "zk") match {
      case InMem.StoreName => InMem(conf)
      case CuratorZk.StoreName => CuratorZk(conf, lifecycleState)
    }
  }
}
