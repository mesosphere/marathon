package mesosphere.marathon.core.plugin

import org.rogach.scallop.ScallopConf

trait PluginConfiguration extends ScallopConf {
  lazy val pluginDir = opt[String]("plugin_dir",
    descr = "Path to a local directory containing plugin jars",
    noshort = true
  )
}
