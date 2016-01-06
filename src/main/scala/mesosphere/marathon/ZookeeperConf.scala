package mesosphere.marathon

import java.net.InetSocketAddress

import org.rogach.scallop.ScallopConf

import scala.concurrent.duration._

trait ZookeeperConf extends ScallopConf {

  //scalastyle:off magic.number

  private val userAndPass = """[^/@]+"""
  private val hostAndPort = """[A-z0-9-.]+(?::\d+)?"""
  private val zkNode = """[^/]+"""
  private val zkURLPattern = s"""^zk://(?:$userAndPass@)?($hostAndPort(?:,$hostAndPort)*)(/$zkNode(?:/$zkNode)*)$$""".r

  lazy val zooKeeperTimeout = opt[Long]("zk_timeout",
    descr = "The timeout for ZooKeeper in milliseconds.",
    default = Some(10 * 1000L)) //10 seconds

  lazy val zooKeeperSessionTimeout = opt[Long]("zk_session_timeout",
    descr = "The timeout for ZooKeeper sessions in milliseconds",
    default = Some(10 * 1000L) //10 seconds
  )

  lazy val zooKeeperUrl = opt[String]("zk",
    descr = "ZooKeeper URL for storing state. Format: zk://host1:port1,host2:port2,.../path",
    validate = (in) => zkURLPattern.pattern.matcher(in).matches(),
    default = Some("zk://localhost:2181/marathon")
  )

  lazy val zooKeeperMaxVersions = opt[Int]("zk_max_versions",
    descr = "Limit the number of versions, stored for one entity.",
    default = Some(25)
  )

  lazy val zooKeeperCompressionEnabled = toggle("zk_compression",
    descrYes =
      "(Default) Enable compression of zk nodes, if the size of the node is bigger than the configured threshold.",
    descrNo = "Disable compression of zk nodes",
    noshort = true,
    prefix = "disable_",
    default = Some(true)
  )

  lazy val zooKeeperCompressionThreshold = opt[Long]("zk_compression_threshold",
    descr = "(Default: 64 KB) Threshold in bytes, when compression is applied to the ZooKeeper node.",
    noshort = true,
    validate = _ >= 0,
    default = Some(64 * 1024)
  )

  def zooKeeperStatePath: String = "%s/state".format(zkPath)
  def zooKeeperLeaderPath: String = "%s/leader".format(zkPath)
  def zooKeeperServerSetPath: String = "%s/apps".format(zkPath)

  def zooKeeperHostAddresses: Seq[InetSocketAddress] =
    for (s <- zkHosts.split(",")) yield {
      val splits = s.split(":")
      require(splits.length == 2, "expected host:port for zk servers")
      new InetSocketAddress(splits(0), splits(1).toInt)
    }

  def zkURL: String = zooKeeperUrl.get.get

  lazy val zkHosts = zkURL match { case zkURLPattern(server, _) => server }
  lazy val zkPath = zkURL match { case zkURLPattern(_, path) => path }
  lazy val zkTimeoutDuration = Duration(zooKeeperTimeout(), MILLISECONDS)
  lazy val zkSessionTimeoutDuration = Duration(zooKeeperSessionTimeout(), MILLISECONDS)
}
