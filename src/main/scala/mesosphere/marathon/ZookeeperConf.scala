package mesosphere.marathon

import org.apache.curator.framework.AuthInfo
import org.apache.zookeeper.ZooDefs
import org.rogach.scallop.{ScallopConf, ValueConverter}

import scala.concurrent.duration._

trait ZookeeperConf extends ScallopConf {
  import ZookeeperConf._

  lazy val zooKeeperTimeout = opt[Long](
    "zk_timeout",
    descr = "The timeout for ZooKeeper operations in milliseconds.",
    default = Some(Duration(10, SECONDS).toMillis))

  lazy val zooKeeperSessionTimeout = opt[Long](
    "zk_session_timeout",
    descr = "The timeout for ZooKeeper sessions in milliseconds",
    default = Some(Duration(10, SECONDS).toMillis)
  )

  lazy val zooKeeperConnectionTimeout = opt[Long](
    "zk_connection_timeout",
    descr = "The timeout to connect to ZooKeeper in milliseconds",
    default = Some(Duration(10, SECONDS).toMillis)
  )

  lazy val zooKeeperUrl = opt[ZkUrl](
    "zk",
    descr = "ZooKeeper URL for storing state. Format: zk://host1:port1,host2:port2,.../path",
    default = Some(ZkUrl.parse("zk://localhost:2181/marathon") match { case Right(u) => u; case _ => ??? /* because scapegoat thought this was better than .right.get */ })
  )(scallopZkUrlParser)

  lazy val zooKeeperCompressionEnabled = toggle(
    "zk_compression",
    descrYes =
      "(Default) Enable compression of zk nodes, if the size of the node is bigger than the configured threshold.",
    descrNo = "Disable compression of zk nodes",
    noshort = true,
    prefix = "disable_",
    default = Some(true)
  )

  lazy val zooKeeperCompressionThreshold = opt[Long](
    "zk_compression_threshold",
    descr = "(Default: 64 KB) Threshold in bytes, when compression is applied to the ZooKeeper node.",
    noshort = true,
    validate = _ >= 0,
    default = Some(64 * 1024)
  )

  lazy val zooKeeperMaxNodeSize = opt[Long](
    "zk_max_node_size",
    descr = "(Default: 1 MiB) Maximum allowed ZooKeeper node size (in bytes).",
    noshort = true,
    validate = _ >= 0,
    default = Some(1024 * 1000)
  )

  lazy val zooKeeperOperationMaxRetries = opt[Int](
    "zk_operation_max_retries",
    descr = "INTERNAL TUNING PARAMETER: " +
      "Maximum number of retries before an operation fails.",
    noshort = true,
    hidden = true,
    default = Some(5)
  )

  lazy val zooKeeperOperationBaseRetrySleepMs = opt[Int](
    "zk_operation_base_retry_sleep_ms",
    descr = "INTERNAL TUNING PARAMETER: " +
      "Base sleep time in milliseconds between operation retries when using exponential retry policy. " +
      "Max sleep time is bounded by the zk_timeout parameter.",
    noshort = true,
    hidden = true,
    default = Some(10)
  )

  def zooKeeperStateUrl: ZkUrl = zooKeeperUrl() / "state"
  def zooKeeperLeaderUrl: ZkUrl = zooKeeperUrl() / "leader"
  def zooKeeperLeaderCuratorUrl: ZkUrl = zooKeeperUrl() / "leader-curator"

  lazy val zkDefaultCreationACL = if (zooKeeperUrl().credentials.nonEmpty)
    ZooDefs.Ids.CREATOR_ALL_ACL
  else
    ZooDefs.Ids.OPEN_ACL_UNSAFE

  lazy val zkTimeoutDuration = Duration(zooKeeperTimeout(), MILLISECONDS)
  lazy val zkSessionTimeoutDuration = Duration(zooKeeperSessionTimeout(), MILLISECONDS)
  lazy val zkConnectionTimeoutDuration = Duration(zooKeeperConnectionTimeout(), MILLISECONDS)
}

object ZookeeperConf {
  val ZkUrlPattern = {
    val user = """[^/:]+"""
    val pass = """[^@]+"""
    val hostAndPort = """[A-z0-9-.]+(?::\d+)?"""
    val zkNode = """[^/]+"""
    s"""^zk://(?:($user):($pass)@)?($hostAndPort(?:,$hostAndPort)*)(/$zkNode(?:/$zkNode)*)$$""".r
  }

  case class ZkCreds(username: String, password: String) {
    def authInfoDigest: AuthInfo =
      new AuthInfo("digest", s"${username}:${password}".getBytes("UTF-8"))
  }

  /**
    * Class which contains a parse zkUrl
    */
  case class ZkUrl(
      credentials: Option[ZkCreds],
      hosts: Seq[String],
      path: String) {

    private def redactedAuthSection = if (credentials.nonEmpty)
      "xxxxxxxx:xxxxxxxx@"
    else
      ""

    private def unredactedAuthSection = credentials match {
      case Some(ZkCreds(username, password)) =>
        s"${username}:${password}@"
      case _ =>
        ""
    }

    def /(subpath: String): ZkUrl = copy(path = path + "/" + subpath)

    def hostsString = hosts.mkString(",")
    /**
      * Render this zkUrl to a string. In order to prevent accidental logging of sensitive credentials in the log, we
      * redact user and password from toString.
      */
    override def toString(): String =
      redactedConnectionString

    def redactedConnectionString =
      s"zk://${redactedAuthSection}${hostsString}${path}"

    def unredactedConnectionString =
      s"zk://${unredactedAuthSection}${hostsString}${path}"

  }

  val scallopZkUrlParser = new ValueConverter[ZkUrl] {
    val argType = org.rogach.scallop.ArgType.SINGLE

    def parse(s: List[(String, List[String])]): Either[String, Option[ZkUrl]] = s match {
      case (_, zkUrlString :: Nil) :: Nil =>
        ZkUrl.parse(zkUrlString).map(Some(_))
      case Nil =>
        Right(None)
      case other =>
        Left("Expected exactly one url")
    }
  }

  object ZkUrl {
    /**
      * Parse a string formatted like zk://user:pass@host1:port,host2:port/path
      *
      * Throws if hosts are not all formatted like 'host:port'
      */
    def parse(url: String): Either[String, ZkUrl] = url match {
      case ZkUrlPattern(user, pass, hosts, path) =>
        // Our regular expression does not match user but no password, or password but no user.
        val creds = for { u <- Option(user); p <- Option(pass) } yield ZkCreds(u, p)
        Right(ZkUrl(creds, hosts.split(",").toList, path))
      case _ =>
        Left(s"${url} is not a valid zk url; it should formatted like zk://user:pass@host1:port1,host2:port2/path, or zk://host1:port1,host2:port2/marathon")
    }
  }
}
