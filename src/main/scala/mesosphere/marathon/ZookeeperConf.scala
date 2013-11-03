package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import java.net.InetSocketAddress

/**
 * @author Tobi Knaup
 */

trait ZookeeperConf extends ScallopConf {

  lazy val zooKeeperHostString = opt[String]("zk_hosts",
    descr = "The list of ZooKeeper servers for storing state",
    default = Some("localhost:2181"))

  lazy val zooKeeperTimeout = opt[Long]("zk_timeout",
    descr = "The timeout for ZooKeeper in milliseconds",
    default = Some(10000L))

  lazy val zooKeeperPath = opt[String]("zk_state",
    descr = "Path in ZooKeeper for storing state",
    default = Some("/marathon"))

  def zooKeeperStatePath = "%s/state".format(zooKeeperPath())

  def zooKeeperLeaderPath = "%s/leader".format(zooKeeperPath())

  def zooKeeperServerSetPath = "%s/apps".format(zooKeeperPath())

  def zooKeeperHostAddresses: Seq[InetSocketAddress] =
    for (s <- zooKeeperHostString().split(",")) yield {
      val splits = s.split(":")
      require(splits.length == 2, "expected host:port for zk servers")
      new InetSocketAddress(splits(0), splits(1).toInt)
    }
}
