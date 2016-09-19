package mesosphere.marathon.metrics

import javax.inject.Inject

import com.codahale.metrics.MetricRegistry
import com.google.common.util.concurrent.AbstractIdleService
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.metrics.MetricsReporter

object MetricsReporterService {

  object QueryParam {
    def unapply(str: String): Option[(String, String)] = str.split("=") match {
      case Array(key: String, value: String) => Some(key -> value)
      case _ => None
    }
  }
}

class MetricsReporterService @Inject() (registry: MetricRegistry, pluginManager: PluginManager)
    extends AbstractIdleService {

  private[this] var reporterPlugins: Seq[MetricsReporter] = Seq.empty[MetricsReporter]

  def startUp(): Unit = {
    reporterPlugins = pluginManager.plugins[MetricsReporter.Factory].flatMap(_(registry))
  }

  def shutDown(): Unit = {
    reporterPlugins.foreach(_.stop())
  }
}
