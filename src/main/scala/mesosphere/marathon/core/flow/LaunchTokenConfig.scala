package mesosphere.marathon.core.flow

import org.rogach.scallop.ScallopConf

trait LaunchTokenConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val launchTokenRefreshInterval = opt[Long]("launch_token_refresh_interval",
    descr = "The interval (ms) in which to refresh the launch tokens to --launch_token_count",
    default = Some(30000))

  lazy val launchTokens = opt[Int]("launch_tokens",
    descr = "Launch tokens per interval",
    default = Some(100))

}
