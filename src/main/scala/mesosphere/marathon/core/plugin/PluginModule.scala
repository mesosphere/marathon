package mesosphere.marathon
package core.plugin

import mesosphere.marathon.core.plugin.impl.PluginManagerImpl
import mesosphere.marathon.plugin.http.HttpRequestHandler

class PluginModule(config: MarathonConf) {

  lazy val pluginManager: PluginManager = PluginManagerImpl(config)

  lazy val httpRequestHandler: Seq[HttpRequestHandler] = pluginManager.plugins[HttpRequestHandler].to[Seq]

}
