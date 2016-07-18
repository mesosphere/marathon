package mesosphere.marathon.core.storage

import java.util
import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import akka.stream.Materializer
import com.typesafe.config.{Config, ConfigMemorySize}
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.storage.impl.BasePersistenceStore
import mesosphere.marathon.core.storage.impl.cache.{LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore}
import mesosphere.marathon.core.storage.impl.memory.{Identity, InMemoryPersistenceStore, RamId}
import mesosphere.marathon.core.storage.impl.zk.{ZkId, ZkPersistenceStore, ZkSerialized}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{EntityStore, EntityStoreCache, MarathonState, MarathonStore}
import mesosphere.marathon.util.{RetryConfig, toRichConfig}
import mesosphere.util.state.PersistentStore
import mesosphere.util.state.memory.InMemoryStore
import mesosphere.util.state.mesos.MesosStateStore
import mesosphere.util.state.zk.{CompressionConf, NoRetryPolicy, RichCuratorFramework, ZKStore}
import org.apache.curator.framework.api.ACLProvider
import org.apache.curator.framework.imps.GzipCompressionProvider
import org.apache.curator.framework.{AuthInfo, CuratorFrameworkFactory}
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, _}
import scala.reflect.ClassTag

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

case class ClassicInMem(maxVersions: Int) extends LegacyStorageConfig {
  def store: PersistentStore = new InMemoryStore()
  val enableCache: Boolean = false
}

object ClassicInMem {
  def apply(config: MarathonConf): ClassicInMem =
    ClassicInMem(maxVersions = config.zooKeeperMaxVersions.get.get)

  def apply(config: Config): ClassicInMem =
    ClassicInMem(maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions))
}

case class ClassicZk(
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
    import com.twitter.zk.{AuthInfo, NativeConnector, ZkClient}

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

object ClassicZk {
  def apply(config: MarathonConf): ClassicZk =
    ClassicZk(
      maxVersions = config.zooKeeperMaxVersions.get.get,
      enableCache = config.storeCache.get.get,
      sessionTimeout = config.zkSessionTimeoutDuration,
      zkHosts = config.zkHosts,
      zkPath = config.zkPath,
      username = config.zkUsername,
      password = config.zkPassword,
      retries = 3,
      enableCompression = config.zooKeeperCompressionEnabled.get.get,
      compressionThreshold = ConfigMemorySize.ofBytes(config.zooKeeperCompressionThreshold.get.get))

  def apply(config: Config): ClassicZk = {
    // scalastyle:off
    ClassicZk(
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
  def apply(config: MarathonConf): MesosZk =
    MesosZk(
      maxVersions = config.zooKeeperMaxVersions.get.get,
      enableCache = config.storeCache.get.get,
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
    case s if s.startsWith("eager") => EagerCaching
    case s if s.startsWith("lazy") => LazyCaching
    case _ => NoCaching
  }
}

sealed trait PersistenceStorageConfig[K, C, S] extends StorageConfig {
  val maxVersions: Int
  val cacheType: CacheType
  protected def leafStore(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler): BasePersistenceStore[K, C, S]

  def store(implicit metrics: Metrics, mat: Materializer,
            ctx: ExecutionContext, scheduler: Scheduler): PersistenceStore[K, C, S] = {
    cacheType match {
      case NoCaching => leafStore
      case LazyCaching => new LazyCachingPersistenceStore[K, C, S](leafStore)
      case EagerCaching => new LoadTimeCachingPersistenceStore[K, C, S](leafStore)
    }
  }
}

case class NewZk(
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
    scheduler: Scheduler): BasePersistenceStore[ZkId, String, ZkSerialized] =
    new ZkPersistenceStore(client, timeout, maxConcurrent)

}

object NewZk {
  def apply(conf: MarathonConf): NewZk =
    NewZk(
      cacheType = if (conf.storeCache.get.get) LazyCaching else NoCaching,
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
      maxVersions = conf.zooKeeperMaxVersions.get.get
    )

  def apply(config: Config): NewZk =
    NewZk(
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
      maxVersions = config.int("max-versions", StorageConfig.DefaultMaxVersions)
    )
}

case class NewInMem(maxVersions: Int) extends PersistenceStorageConfig[RamId, String, Identity] {
  override val cacheType: CacheType = NoCaching

  protected def leafStore(implicit metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
    scheduler: Scheduler): BasePersistenceStore[RamId, String, Identity] =
    new InMemoryPersistenceStore()
}

object NewInMem {
  def apply(conf: MarathonConf): NewInMem =
    NewInMem(conf.zooKeeperMaxVersions.get.get)

  def apply(conf: Config): NewInMem =
    NewInMem(conf.int("max-versions", StorageConfig.DefaultMaxVersions))
}

object StorageConfig {
  val DefaultMaxVersions = 25
  def apply(conf: MarathonConf): StorageConfig = {
    conf.internalStoreBackend.get.get match {
      case "zk" => ClassicZk(conf)
      case "mesos_zk" => MesosZk(conf)
      case "mem" => ClassicInMem(conf)
      case "new_zk" => NewZk(conf)
      case "new_mem" => NewInMem(conf)
    }
  }

  def apply(conf: Config): StorageConfig = {
    conf.string("storage-type", "zk") match {
      case "zk" => ClassicZk(conf)
      case "mesos_zk" => MesosZk(conf)
      case "mem" => ClassicInMem(conf)
      case "new_zk" => NewZk(conf)
      case "new_mem" => NewInMem(conf)
    }
  }
}
