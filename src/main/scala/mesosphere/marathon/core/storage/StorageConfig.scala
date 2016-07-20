package mesosphere.marathon.core.storage

// scalastyle:off
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.{ Config, ConfigMemorySize }
import mesosphere.marathon.core.storage.repository.impl.legacy.store.{ CompressionConf, EntityStore, EntityStoreCache, MarathonStore, MesosStateStore, PersistentStore, ZKStore }
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryPersistenceStore, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ NoRetryPolicy, RichCuratorFramework, ZkId, ZkPersistenceStore, ZkSerialized }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.util.{ RetryConfig, toRichConfig }
import mesosphere.marathon.{ MarathonConf, ZookeeperConf }
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.GzipCompressionProvider
import org.apache.curator.framework.{ AuthInfo, CuratorFrameworkFactory }
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ Duration, _ }
import scala.reflect.ClassTag
// scalastyle:on

sealed trait StorageConfig
sealed trait LegacyStorageConfig extends StorageConfig {
  protected def store: PersistentStore
  val maxVersions: Int
  val enableCache: Boolean

  def entityStore[T <: MarathonState[_, T]](prefix: String, newState: () => T)(
    implicit
    metrics: Metrics, ct: ClassTag[T]): EntityStore[T] = {
    val marathonStore = new MarathonStore[T](store, metrics, newState, prefix)
    if (enableCache) new EntityStoreCache[T](marathonStore) else marathonStore
  }
}

case class TwitterZk(
    maxVersions: Int,
    enableCache: Boolean,
    sessionTimeout: Duration,
    zkHosts: String,
    zkPath: String,
    username: Option[String],
    password: Option[String],
    retries: Int,
    enableCompression: Boolean,
    compressionThreshold: ConfigMemorySize) extends LegacyStorageConfig {

  private val sessionTimeoutTw = {
    com.twitter.util.Duration(sessionTimeout.toMillis, TimeUnit.MILLISECONDS)
  }
  protected def store: PersistentStore = {
    import com.twitter.util.JavaTimer
    import com.twitter.zk.{ AuthInfo, NativeConnector, ZkClient }

    val authInfo = (username, password) match {
      case (Some(user), Some(pass)) => Some(AuthInfo.digest(user, pass))
      case _ => None
    }

    val creationAcl = (username, password) match {
      case (Some(user), Some(pass)) =>
        ZooDefs.Ids.CREATOR_ALL_ACL
      case _ =>
        ZooDefs.Ids.OPEN_ACL_UNSAFE
    }

    val connector = NativeConnector(zkHosts, None, sessionTimeoutTw, new JavaTimer(isDaemon = true), authInfo)

    val client = ZkClient(connector)
      .withAcl(creationAcl)
      .withRetries(retries)
    val compressionConf = CompressionConf(enableCompression, compressionThreshold.toBytes)
    new ZKStore(client, client(zkPath), compressionConf)
  }
}

object TwitterZk {
  def apply(cache: Boolean, config: ZookeeperConf): TwitterZk =
    TwitterZk(
      maxVersions = config.zooKeeperMaxVersions(),
      enableCache = cache,
      sessionTimeout = config.zkSessionTimeoutDuration,
      zkHosts = config.zkHosts,
      zkPath = config.zkPath,
      username = config.zkUsername,
      password = config.zkPassword,
      retries = 3,
      enableCompression = config.zooKeeperCompressionEnabled(),
      compressionThreshold = ConfigMemorySize.ofBytes(config.zooKeeperCompressionThreshold()))

  def apply(config: Config): TwitterZk = {
    // scalastyle:off
    TwitterZk(
      maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions),
      enableCache = config.bool("enable-cache", true),
      sessionTimeout = config.duration("session-timeout", 10.seconds),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = config.string("path", "marathon"),
      username = config.optionalString("username"),
      password = config.optionalString("password"),
      retries = config.int("retries", 3),
      enableCompression = config.bool("enable-compression", true),
      compressionThreshold = config.memorySize("compression-threshold", ConfigMemorySize.ofBytes(64 * 1024))
    )
    // scalastyle:on
  }
}

case class MesosZk(
    maxVersions: Int,
    enableCache: Boolean,
    zkHosts: String,
    zkPath: String,
    timeout: Duration) extends LegacyStorageConfig {
  def store: PersistentStore = {
    val state = new ZooKeeperState(
      zkHosts,
      timeout.toMillis,
      TimeUnit.MILLISECONDS,
      zkPath
    )
    new MesosStateStore(state, timeout)
  }
}

object MesosZk {
  def apply(cache: Boolean, config: ZookeeperConf): MesosZk =
    MesosZk(
      maxVersions = config.zooKeeperMaxVersions(),
      enableCache = cache,
      zkHosts = config.zkHosts,
      zkPath = config.zkPath,
      timeout = config.zkTimeoutDuration)

  def apply(config: Config): MesosZk =
    MesosZk(
      maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions),
      enableCache = config.bool("enable-cache", true),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = config.string("path", "marathon"),
      timeout = config.duration("timeout", 10.seconds)
    )
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
  protected def leafStore(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): BasePersistenceStore[K, C, S]

  def store(implicit metrics: Metrics, mat: Materializer,
    ctx: ExecutionContext, scheduler: Scheduler, actorRefFactory: ActorRefFactory): PersistenceStore[K, C, S] = {
    cacheType match {
      case NoCaching => leafStore
      case LazyCaching => new LazyCachingPersistenceStore[K, C, S](leafStore)
      case EagerCaching => new LoadTimeCachingPersistenceStore[K, C, S](leafStore)
    }
  }
}

case class CuratorZk(
    cacheType: CacheType,
    sessionTimeout: Option[Duration],
    connectionTimeout: Option[Duration],
    timeout: Duration,
    zkHosts: String,
    zkPath: String,
    username: Option[String],
    password: Option[String],
    enableCompression: Boolean,
    retryConfig: RetryConfig,
    maxConcurrent: Int,
    maxOutstanding: Int,
    maxVersions: Int) extends PersistenceStorageConfig[ZkId, String, ZkSerialized] {

  lazy val client: RichCuratorFramework = {
    val builder = CuratorFrameworkFactory.builder()
    builder.connectString(zkHosts)
    sessionTimeout.foreach(t => builder.sessionTimeoutMs(t.toMillis.toInt))
    connectionTimeout.foreach(t => builder.connectionTimeoutMs(t.toMillis.toInt))
    if (enableCompression) builder.compressionProvider(new GzipCompressionProvider)
    val defaultAcls = (username, password) match {
      case (Some(user), Some(pass)) =>
        builder.authorization(Seq(new AuthInfo("digest", s"$user:$pass".getBytes("UTF-8"))))
        ZooDefs.Ids.CREATOR_ALL_ACL
      case _ =>
        ZooDefs.Ids.OPEN_ACL_UNSAFE
    }
    builder.aclProvider(new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = defaultAcls

      override def getAclForPath(path: String): util.List[ACL] = defaultAcls
    })
    builder.retryPolicy(NoRetryPolicy) // We use our own Retry.
    builder.namespace(zkPath)
    val client = builder.build()
    client.start()
    client.blockUntilConnected()
    RichCuratorFramework(client)
  }

  protected def leafStore(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): BasePersistenceStore[ZkId, String, ZkSerialized] =
    new ZkPersistenceStore(client, timeout, maxConcurrent, maxOutstanding)

}

object CuratorZk {
  def apply(cache: Boolean, conf: ZookeeperConf): CuratorZk =
    CuratorZk(
      cacheType = if (cache) LazyCaching else NoCaching,
      sessionTimeout = Some(conf.zkSessionTimeoutDuration),
      connectionTimeout = None,
      timeout = conf.zkTimeoutDuration,
      zkHosts = conf.zkHosts,
      zkPath = conf.zkPath,
      username = conf.zkUsername,
      password = conf.zkUsername,
      enableCompression = conf.zooKeeperCompressionEnabled.get.get,
      retryConfig = RetryConfig(),
      maxConcurrent = 8,
      maxOutstanding = 1024, // scalastyle:off magic.number
      maxVersions = conf.zooKeeperMaxVersions.get.get
    )

  def apply(config: Config): CuratorZk =
    CuratorZk(
      cacheType = CacheType(config.string("cache-type", "lazy")),
      sessionTimeout = config.optionalDuration("session-timeout"),
      connectionTimeout = config.optionalDuration("connect-timeout"),
      timeout = config.duration("timeout", 10.seconds),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = config.string("path", "marathon"),
      username = config.optionalString("username"),
      password = config.optionalString("password"),
      enableCompression = config.bool("enable-compression", true),
      retryConfig = RetryConfig(config),
      maxConcurrent = config.int("max-concurrent-requests", 8),
      maxOutstanding = config.int("max-concurrent-outstanding", 1024), // scalastyle:off magic.number
      maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions)
    )
}

case class InMem(maxVersions: Int) extends PersistenceStorageConfig[RamId, String, Identity] {
  override val cacheType: CacheType = NoCaching

  protected def leafStore(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): BasePersistenceStore[RamId, String, Identity] =
    new InMemoryPersistenceStore()
}

object InMem {
  def apply(conf: ZookeeperConf): InMem =
    InMem(conf.zooKeeperMaxVersions())

  def apply(conf: Config): InMem =
    InMem(conf.int("max-versions", StorageConfig.DefaultMaxVersions))
}

object StorageConfig {
  val DefaultMaxVersions = 25
  def apply(conf: MarathonConf): StorageConfig = {
    conf.internalStoreBackend() match {
      case "zk" => TwitterZk(conf.storeCache(), conf)
      case "mesos_zk" => MesosZk(conf.storeCache(), conf)
      case "mem" => InMem(conf)
      case "zk2" => CuratorZk(conf.storeCache(), conf)
    }
  }

  def apply(conf: Config): StorageConfig = {
    conf.string("storage-type", "zk") match {
      case "zk" => TwitterZk(conf)
      case "mesos_zk" => MesosZk(conf)
      case "mem" => InMem(conf)
      case "zk2" => CuratorZk(conf)
    }
  }
}
