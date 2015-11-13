package mesosphere.marathon.core.plugin

import org.rogach.scallop.ScallopConf

trait PluginManagerConfiguration extends ScallopConf {

  lazy val pluginConf = opt[String]("plugin_conf",
    descr = "The plugin configuration file.",
    noshort = true
  )

  lazy val pluginDir = opt[String]("plugin_dir",
    descr = "Path to a local directory containing plugin jars.",
    noshort = true
  )

  codependent(pluginConf, pluginDir)
}
