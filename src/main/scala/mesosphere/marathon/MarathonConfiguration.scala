package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import java.net.InetSocketAddress

/**
 * @author Tobi Knaup
 */

trait MarathonConfiguration extends ScallopConf {


  lazy val mesosMaster = opt[String]("master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

  lazy val mesosFailoverTimeout = opt[Long]("failover_timeout",
    descr = "The failover_timeout for mesos in seconds",
    default = Some(3600L))

  lazy val highlyAvailable = opt[Boolean]("ha",
    descr = "Runs Marathon in HA mode with leader election. " +
      "Allows starting an arbitrary number of other Marathons but all need " +
      "to be started in HA mode. This mode requires a running ZooKeeper",
    noshort = true, default = Some(true))

  lazy val zooKeeperHostString = opt[String]("zk_hosts",
    descr = "The list of ZooKeeper servers for storing state",
    default = Some("localhost:2181"))

  lazy val zooKeeperTimeout = opt[Long]("zk_timeout",
    descr = "The timeout for ZooKeeper in milliseconds",
    default = Some(10000L))

  lazy val zooKeeperPath = opt[String]("zk_state",
    descr = "Path in ZooKeeper for storing state",
    default = Some("/marathon"))

  lazy val localPortMin = opt[Int]("local_port_min",
    descr = "Min port number to use when assigning ports to apps",
    default = Some(10000))

  lazy val localPortMax = opt[Int]("local_port_max",
    descr = "Max port number to use when assigning ports to apps",
    default = Some(20000))

  def zooKeeperStatePath = "%s/state".format(zooKeeperPath())

  def zooKeeperLeaderPath = "%s/leader".format(zooKeeperPath())

  def zooKeeperServerSetPath = "%s/apps".format(zooKeeperPath())

  lazy val hostname = opt[String]("hostname",
    descr = "The advertised hostname stored in ZooKeeper so another standby " +
      "host can redirect to this elected leader",
    default = Some("localhost"))

  def zooKeeperHostAddresses: Seq[InetSocketAddress] =
    for (s <- zooKeeperHostString().split(",")) yield {
      val splits = s.split(":")
      require(splits.length == 2, "expected host:port for zk servers")
      new InetSocketAddress(splits(0), splits(1).toInt)
    }
}
