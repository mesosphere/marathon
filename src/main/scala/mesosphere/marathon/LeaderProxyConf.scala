package mesosphere.marathon

import org.rogach.scallop.ScallopConf

/**
  * Configuration for proxying to the current leader.
  */
trait LeaderProxyConf extends ScallopConf {

  //scalastyle:off magic.number

  lazy val leaderProxyConnectionTimeout = opt[Int](
    "leader_proxy_connection_timeout",
    descr = "Maximum time, in milliseconds, to wait for connecting to the current Marathon leader from " +
      "another Marathon instance.",
    default = Some(5000)) // 5 seconds

  lazy val leaderProxyReadTimeout = opt[Int](
    "leader_proxy_read_timeout",
    descr = "Maximum time, in milliseconds, for reading from the current Marathon leader.",
    default = Some(10000)) // 10 seconds

  lazy val leaderProxySSLIgnoreHostname = opt[Boolean](
    "leader_proxy_ssl_ignore_hostname",
    descr = "Do not verify that the hostname of the Marathon leader matches the one in the SSL certificate" +
      " when proxying API requests to the current leader.",
    default = Some(false))
}
