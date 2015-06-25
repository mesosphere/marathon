package mesosphere.marathon

import org.rogach.scallop.ScallopConf

/**
  * Configuration for proxying to the current leader.
  */
trait LeaderProxyConf extends ScallopConf {

  //scalastyle:off magic.number

  lazy val leaderProxyConnectionTimeout = opt[Int]("leader_proxy_connection_timeout",
    descr = "Maximum time, in milliseconds, to wait for connecting to the current Marathon leader from " +
      "another Marathon instance.",
    default = Some(5000)) // 5 seconds

  lazy val leaderProxyReadTimeout = opt[Int]("leader_proxy_read_timeout",
    descr = "Maximum time, in milliseconds, for reading from the current Marathon leader.",
    default = Some(10000)) // 10 seconds

}
