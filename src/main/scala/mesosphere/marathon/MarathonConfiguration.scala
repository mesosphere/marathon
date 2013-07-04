package mesosphere.marathon

import org.rogach.scallop.ScallopConf

/**
 * @author Tobi Knaup
 */

trait MarathonConfiguration extends ScallopConf {

  lazy val mesosMaster = opt[String]("master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

  lazy val zooKeeperHosts = opt[String]("zk_hosts",
    descr = "The list of ZooKeeper servers for storing state",
    default = Some("localhost:2181"))

  lazy val zooKeeperTimeout = opt[Long]("zk_timeout",
    descr = "The timeout for ZooKeeper in seconds",
    default = Some(10L))

  lazy val zooKeeperPath = opt[String]("zk_path",
    descr = "Path in ZooKeeper for storing state",
    default = Some("/marathon-state"))

}