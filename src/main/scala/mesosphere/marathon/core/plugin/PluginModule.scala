package mesosphere.marathon.core.plugin

import mesosphere.marathon.core.plugin.impl.PluginManagerImpl

class PluginModule(config: PluginManagerConfiguration) {

  lazy val pluginManager: PluginManager = PluginManagerImpl(config)

}
