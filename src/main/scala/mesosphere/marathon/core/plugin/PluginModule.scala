package mesosphere.marathon
package core.plugin

import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.core.plugin.impl.PluginManagerImpl
import mesosphere.marathon.plugin.http.HttpRequestHandler
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.validation.RunSpecValidator

class PluginModule(config: MarathonConf, crashStrategy: CrashStrategy) {

  lazy val pluginManager: PluginManager = PluginManagerImpl(config, crashStrategy)

  lazy val httpRequestHandler: Seq[HttpRequestHandler] = pluginManager.plugins[HttpRequestHandler]

  /**
    * This list of plugins are eagerly loaded at marathon-init time so that configuration failure
    * is detected in boot process and Marathon will fail to start.
    *
    * It is likely long term to have an opt-in strategy for plugins that wish to have this behavior.
    */
  private[this] def initPlugins(): Unit = {
    pluginManager.plugins[RunSpecTaskProcessor]
    pluginManager.plugins[RunSpecValidator]
  }

  initPlugins()
}
