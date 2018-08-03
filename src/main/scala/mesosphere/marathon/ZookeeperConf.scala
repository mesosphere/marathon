package mesosphere.marathon

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

  lazy val zooKeeperUrl = opt[ZKUrl](
    "zk",
    descr = "ZooKeeper URL for storing state. Format: zk://host1:port1,host2:port2,.../path",
    default = Some(ZKUrl.parse("zk://localhost:2181/marathon").right.toOption.get)
  )(scallopZKUrlParser)

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

  def zooKeeperStatePath: String = "%s/state".format(zooKeeperUrl().path)
  def zooKeeperLeaderPath: String = "%s/leader".format(zooKeeperUrl().path)
  def zooKeeperServerSetPath: String = "%s/apps".format(zooKeeperUrl().path)

  lazy val zkDefaultCreationACL = (zooKeeperUrl().username, zooKeeperUrl().password) match {
    case (Some(_), Some(_)) => ZooDefs.Ids.CREATOR_ALL_ACL
    case _ => ZooDefs.Ids.OPEN_ACL_UNSAFE
  }

  lazy val zkTimeoutDuration = Duration(zooKeeperTimeout(), MILLISECONDS)
  lazy val zkSessionTimeoutDuration = Duration(zooKeeperSessionTimeout(), MILLISECONDS)
  lazy val zkConnectionTimeoutDuration = Duration(zooKeeperConnectionTimeout(), MILLISECONDS)
}

object ZookeeperConf {
  private val user = """[^/:]+"""
  private val pass = """[^@]+"""
  private val hostAndPort = """[A-z0-9-.]+(?::\d+)?"""
  private val zkNode = """[^/]+"""
  val ZKUrlPattern = s"""^zk://(?:($user):($pass)@)?($hostAndPort(?:,$hostAndPort)*)(/$zkNode(?:/$zkNode)*)$$""".r

  /**
    * Class which contains a parse zkUrl
    */
  case class ZKUrl(
      username: Option[String],
      password: Option[String],
      hosts: Seq[String],
      path: String) {

    private def redactedAuthSection = if (username.nonEmpty)
      "xxxxxxxx:xxxxxxxx@"
    else
      ""

    private def unredactedAuthSection = if (username.nonEmpty || password.nonEmpty)
      s"${username.getOrElse("")}:${password.getOrElse("")}@"
    else
      ""

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

  val scallopZKUrlParser = new ValueConverter[ZKUrl] {
    val argType = org.rogach.scallop.ArgType.SINGLE

    def parse(s: List[(String, List[String])]): Either[String, Option[ZKUrl]] = s match {
      case (_, zkUrlString :: Nil) :: Nil =>
        ZKUrl.parse(zkUrlString).map(Some(_))
      case Nil =>
        Right(None)
      case other =>
        Left("Expected exactly one url")
    }
  }

  object ZKUrl {
    /**
      * Parse a string formatted like zk://user:pass@host1:port,host2:port/path
      *
      * Throws if hosts are not all formatted like 'host:port'
      */
    def parse(url: String): Either[String, ZKUrl] = url match {
      case ZKUrlPattern(user, pass, hosts, path) =>
        Right(ZKUrl(Option(user), Option(pass), hosts.split(",").toList, path))
      case _ =>
        Left(s"${url} is not a valid zk url; it should formatted like zk://user:pass@host1:port1,host2:port2/path, or zk://host1:port1,host2:port2/marathon")
    }
  }
}
