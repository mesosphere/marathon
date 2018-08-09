package mesosphere.marathon

import mesosphere.marathon.core.event.EventConf
import mesosphere.marathon.core.plugin.PluginManagerConfiguration
import mesosphere.marathon.metrics.MetricsConf
import org.rogach.scallop.ScallopConf

class AllConf(args: Seq[String] = Nil) extends ScallopConf(args)
  with MetricsConf
  with HttpConf
  with MarathonConf
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
