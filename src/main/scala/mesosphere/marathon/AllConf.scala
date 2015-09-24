package mesosphere.marathon

import mesosphere.chaos.AppConfiguration
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.plugin.PluginConfiguration
import mesosphere.marathon.event.EventConfiguration
import mesosphere.marathon.event.http.HttpEventConfiguration
import org.rogach.scallop.ScallopConf

class AllConf(args: Seq[String] = Nil) extends ScallopConf(args)
  with HttpConf
  with MarathonConf
  with AppConfiguration
  with EventConfiguration
  with HttpEventConfiguration
  with DebugConf
  with PluginConfiguration
