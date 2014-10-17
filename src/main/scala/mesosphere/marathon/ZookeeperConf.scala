package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import java.net.InetSocketAddress
import mesosphere.util.BackToTheFuture
import scala.concurrent.duration._

trait ZookeeperConf extends ScallopConf {

  private val userAndPass = """[^/@]+"""
  private val hostAndPort = """[A-z0-9-.]+(?::\d+)?"""
  private val zkNode = """[^/]+"""
  private val zkURLPattern = s"""^zk://(?:$userAndPass@)?($hostAndPort(?:,$hostAndPort)*)(/$zkNode(?:/$zkNode)*)$$""".r

  @Deprecated
  lazy val zooKeeperHostString = opt[String]("zk_hosts",
    descr = "[DEPRECATED use zk] The list of ZooKeeper servers for storing state",
    default = Some("localhost:2181"))

  @Deprecated
  lazy val zooKeeperPath = opt[String]("zk_state",
    descr = "[DEPRECATED use zk] Path in ZooKeeper for storing state",
    default = Some("/marathon"))

  lazy val zooKeeperTimeout = opt[Long]("zk_timeout",
    descr = "The timeout for ZooKeeper in milliseconds",
    default = Some(10000L))

  lazy val zooKeeperUrl = opt[String]("zk",
    descr = "ZooKeeper URL for storing state. Format: zk://host1:port1,host2:port2,.../path",
    validate = (in) => zkURLPattern.pattern.matcher(in).matches()
  )

  lazy val zooKeeperMaxVersions = opt[Int]("zk_max_versions",
    descr = "Limit the number of versions, stored for one entity."
  )

  //do not allow mixing of hostState and url
  conflicts(zooKeeperHostString, List(zooKeeperUrl))
  conflicts(zooKeeperPath, List(zooKeeperUrl))
  conflicts(zooKeeperUrl, List(zooKeeperHostString, zooKeeperPath))

  def zooKeeperStatePath(): String = "%s/state".format(zkPath)
  def zooKeeperLeaderPath(): String = "%s/leader".format(zkPath)
  def zooKeeperServerSetPath(): String = "%s/apps".format(zkPath)

  def zooKeeperHostAddresses: Seq[InetSocketAddress] =
    for (s <- zkHosts.split(",")) yield {
      val splits = s.split(":")
      require(splits.length == 2, "expected host:port for zk servers")
      new InetSocketAddress(splits(0), splits(1).toInt)
    }

  def zkURL(): String = zooKeeperUrl.get.getOrElse(s"zk://${zooKeeperHostString()}${zooKeeperPath()}")

  lazy val zkHosts = zkURL match { case zkURLPattern(server, _) => server }
  lazy val zkPath = zkURL match { case zkURLPattern(_, path) => path }
  lazy val zkTimeoutDuration = Duration(zooKeeperTimeout(), MILLISECONDS)
  lazy val zkFutureTimeout = BackToTheFuture.Timeout(zkTimeoutDuration)
}
