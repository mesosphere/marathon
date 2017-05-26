package mesosphere.marathon

import mesosphere.chaos.AppConfiguration
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.event.EventConf
import mesosphere.marathon.core.plugin.PluginManagerConfiguration
import mesosphere.marathon.metrics.MetricsReporterConf
import org.rogach.scallop.ScallopConf

class AllConf(args: Seq[String] = Nil) extends ScallopConf(args)
    with MetricsReporterConf
    with HttpConf
    with MarathonConf
    with AppConfiguration
    with EventConf
    with DebugConf
    with PluginManagerConfiguration
    with FeaturesConf {
  verify()
}

object AllConf {
  def apply(args: String*): AllConf = {
    new AllConf(args.to[Seq])
  }

  def withTestConfig(args: String*): AllConf = {
    new AllConf(Seq("--master", "local") ++ args)
  }
}
