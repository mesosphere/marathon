package mesosphere.marathon
package core.plugin

import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.core.plugin.impl.PluginManagerImpl
import mesosphere.marathon.plugin.http.HttpRequestHandler

class PluginModule(config: MarathonConf, crashStrategy: CrashStrategy) {

  lazy val pluginManager: PluginManager = PluginManagerImpl(config, crashStrategy)

  lazy val httpRequestHandler: Seq[HttpRequestHandler] = pluginManager.plugins[HttpRequestHandler]

}
