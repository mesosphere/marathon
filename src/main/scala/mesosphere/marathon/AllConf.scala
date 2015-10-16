package mesosphere.marathon

import mesosphere.chaos.AppConfiguration
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.plugin.PluginManagerConfiguration
import mesosphere.marathon.event.EventConfiguration
import mesosphere.marathon.event.http.HttpEventConfiguration
import mesosphere.marathon.metrics.MetricsConf
import org.rogach.scallop.ScallopConf

class AllConf(args: Seq[String] = Nil) extends ScallopConf(args)
  with MetricsConf
  with HttpConf
  with MarathonConf
  with AppConfiguration
  with EventConfiguration
  with HttpEventConfiguration
  with DebugConf
  with PluginManagerConfiguration
