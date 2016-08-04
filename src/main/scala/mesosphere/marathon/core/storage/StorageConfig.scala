package mesosphere.marathon.core.storage

// scalastyle:off
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{ ActorRefFactory, Scheduler }
import akka.stream.Materializer
import com.typesafe.config.{ Config, ConfigMemorySize }
import mesosphere.marathon.core.storage.repository.impl.legacy.store._
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryPersistenceStore, RamId }
import mesosphere.marathon.core.storage.store.impl.zk.{ NoRetryPolicy, RichCuratorFramework, ZkId, ZkPersistenceStore, ZkSerialized }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.util.{ RetryConfig, toRichConfig }
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

sealed trait StorageConfig extends Product with Serializable
sealed trait LegacyStorageConfig extends StorageConfig {
  protected[storage] def store: PersistentStore
  val maxVersions: Int
  val enableCache: Boolean

  def entityStore[T <: MarathonState[_, T]](prefix: String, newState: () => T)(
    implicit
    metrics: Metrics, ct: ClassTag[T]): EntityStore[T] = {
    val marathonStore = new MarathonStore[T](store, metrics, newState, prefix)
    if (enableCache) new EntityStoreCache[T](marathonStore) else marathonStore
  }
}

// only for testing
private[storage] case class LegacyInMemConfig(maxVersions: Int) extends LegacyStorageConfig {
  override protected[storage] val store: PersistentStore = new InMemoryStore()
  override val enableCache: Boolean = false
}

case class TwitterZk(
    maxVersions: Int,
    enableCache: Boolean,
    sessionTimeout: Duration,
    zkHosts: String,
    zkPath: String,
    zkAcl: util.List[ACL],
    username: Option[String],
    password: Option[String],
    retries: Int,
    enableCompression: Boolean,
    compressionThreshold: ConfigMemorySize,
    maxConcurrent: Int,
    maxOutstanding: Int)(implicit metrics: Metrics, actorRefFactory: ActorRefFactory) extends LegacyStorageConfig {

  private val sessionTimeoutTw = {
    com.twitter.util.Duration(sessionTimeout.toMillis, TimeUnit.MILLISECONDS)
  }

  protected[storage] lazy val store: PersistentStore = {
    import com.twitter.util.JavaTimer
    import com.twitter.zk.{ AuthInfo, NativeConnector, ZkClient }

    val authInfo = (username, password) match {
      case (Some(user), Some(pass)) => Some(AuthInfo.digest(user, pass))
      case _ => None
    }

    val connector = NativeConnector(zkHosts, None, sessionTimeoutTw, new JavaTimer(isDaemon = true), authInfo)

    val client = ZkClient(connector)
      .withAcl(zkAcl)
      .withRetries(retries)
    val compressionConf = CompressionConf(enableCompression, compressionThreshold.toBytes)
    new ZKStore(client, client(zkPath), compressionConf, maxConcurrent = maxConcurrent, maxOutstanding = maxOutstanding)
  }
}

object TwitterZk {
  val StoreName = "legacy_zk"

  def apply(
    config: StorageConf)(implicit metrics: Metrics, actorRefFactory: ActorRefFactory): TwitterZk =
    TwitterZk(
      maxVersions = config.maxVersions(),
      enableCache = config.storeCache(),
      sessionTimeout = config.zkSessionTimeoutDuration,
      zkHosts = config.zkHosts,
      zkPath = config.zooKeeperStatePath,
      zkAcl = config.zkDefaultCreationACL,
      username = config.zkUsername,
      password = config.zkPassword,
      retries = 3,
      enableCompression = config.zooKeeperCompressionEnabled(),
      compressionThreshold = ConfigMemorySize.ofBytes(config.zooKeeperCompressionThreshold()),
      maxConcurrent = config.zkMaxConcurrency(),
      maxOutstanding = 1024) // scalastyle:off magic.number

  def apply(config: Config)(implicit metrics: Metrics, actorRefFactory: ActorRefFactory): TwitterZk = {
    val username = config.optionalString("username")
    val password = config.optionalString("password")
    val acls = (username, password) match {
      case (Some(_), Some(_)) => ZooDefs.Ids.CREATOR_ALL_ACL
      case _ => ZooDefs.Ids.OPEN_ACL_UNSAFE
    }
    // scalastyle:off
    TwitterZk(
      maxVersions = config.int("max-versions", StorageConfig.DefaultLegacyMaxVersions),
      enableCache = config.bool("enable-cache", true),
      sessionTimeout = config.duration("session-timeout", 10.seconds),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = s"${config.string("path", "marathon")}/state",
      zkAcl = acls,
      username = username,
      password = password,
      retries = config.int("retries", 3),
      enableCompression = config.bool("enable-compression", true),
      compressionThreshold = config.memorySize("compression-threshold", ConfigMemorySize.ofBytes(64 * 1024)),
      maxConcurrent = config.int("max-concurrent", 32),
      maxOutstanding = config.int("max-outstanding", 1024)
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
  val StoreName = "mesos_zk"

  def apply(config: StorageConf): MesosZk =
    MesosZk(
      maxVersions = config.maxVersions(),
      enableCache = config.storeCache(),
      zkHosts = config.zkHosts,
      zkPath = config.zooKeeperStatePath,
      timeout = config.zkTimeoutDuration)

  def apply(config: Config): MesosZk =
    MesosZk(
      maxVersions = config.int("max-versions", StorageConfig.DefaultLegacyMaxVersions),
      enableCache = config.bool("enable-cache", true),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = s"${config.string("path", "marathon")}/state",
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
    zkAcls: util.List[ACL],
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
    (username, password) match {
      case (Some(user), Some(pass)) =>
        builder.authorization(Seq(new AuthInfo("digest", s"$user:$pass".getBytes("UTF-8"))))
      case _ =>
    }
    builder.aclProvider(new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = zkAcls

      override def getAclForPath(path: String): util.List[ACL] = zkAcls
    })
    builder.retryPolicy(NoRetryPolicy) // We use our own Retry.
    builder.namespace(zkPath.replaceAll("^/", ""))
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
  val StoreName = "zk"
  def apply(conf: StorageConf): CuratorZk =
    CuratorZk(
      cacheType = if (conf.storeCache()) LazyCaching else NoCaching,
      sessionTimeout = Some(conf.zkSessionTimeoutDuration),
      connectionTimeout = None,
      timeout = conf.zkTimeoutDuration,
      zkHosts = conf.zkHosts,
      zkPath = conf.zooKeeperStatePath,
      zkAcls = conf.zkDefaultCreationACL,
      username = conf.zkUsername,
      password = conf.zkUsername,
      enableCompression = conf.zooKeeperCompressionEnabled(),
      retryConfig = RetryConfig(),
      maxConcurrent = conf.zkMaxConcurrency(),
      maxOutstanding = 1024, // scalastyle:off magic.number
      maxVersions = conf.maxVersions()
    )

  def apply(config: Config): CuratorZk = {
    val username = config.optionalString("username")
    val password = config.optionalString("password")
    val acls = (username, password) match {
      case (Some(_), Some(_)) => ZooDefs.Ids.CREATOR_ALL_ACL
      case _ => ZooDefs.Ids.OPEN_ACL_UNSAFE
    }
    CuratorZk(
      cacheType = CacheType(config.string("cache-type", "lazy")),
      sessionTimeout = config.optionalDuration("session-timeout"),
      connectionTimeout = config.optionalDuration("connect-timeout"),
      timeout = config.duration("timeout", 10.seconds),
      zkHosts = config.stringList("hosts", Seq("localhost:2181")).mkString(","),
      zkPath = s"${config.string("path", "marathon")}/state",
      zkAcls = acls,
      username = username,
      password = password,
      enableCompression = config.bool("enable-compression", true),
      retryConfig = RetryConfig(config),
      maxConcurrent = config.int("max-concurrent-requests", 32), // scalastyle:off magic.number
      maxOutstanding = config.int("max-concurrent-outstanding", 1024), // scalastyle:off magic.number
      maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions)
    )
  }
}

case class InMem(maxVersions: Int) extends PersistenceStorageConfig[RamId, String, Identity] {
  override val cacheType: CacheType = NoCaching

  protected def leafStore(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler, actorRefFactory: ActorRefFactory): BasePersistenceStore[RamId, String, Identity] =
    new InMemoryPersistenceStore()
}

object InMem {
  val StoreName = "mem"

  def apply(conf: StorageConf): InMem =
    InMem(conf.maxVersions())

  def apply(conf: Config): InMem =
    InMem(conf.int("max-versions", StorageConfig.DefaultMaxVersions))
}

object StorageConfig {
  val DefaultLegacyMaxVersions = 25
  val DefaultMaxVersions = 5000
  def apply(conf: StorageConf)(implicit metrics: Metrics, actorRefFactory: ActorRefFactory): StorageConfig = {
    conf.internalStoreBackend() match {
      case TwitterZk.StoreName => TwitterZk(conf)
      case MesosZk.StoreName => MesosZk(conf)
      case InMem.StoreName => InMem(conf)
      case CuratorZk.StoreName => CuratorZk(conf)
    }
  }

  def apply(conf: Config)(implicit metrics: Metrics, actorRefFactory: ActorRefFactory): StorageConfig = {
    conf.string("storage-type", "zk") match {
      case TwitterZk.StoreName => TwitterZk(conf)
      case MesosZk.StoreName => MesosZk(conf)
      case InMem.StoreName => InMem(conf)
      case CuratorZk.StoreName => CuratorZk(conf)
    }
  }
}
