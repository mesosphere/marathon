package mesosphere.marathon.metrics

import com.google.inject.{ AbstractModule, Provides, Scopes, Singleton }
import org.apache.hadoop.metrics.util.MetricsRegistry

class MetricsReporterModule(metricsConf: MetricsConf) extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[MetricsConf]).toInstance(metricsConf)
    bind(classOf[MetricsReporterService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideMetricsRegistry(): MetricsRegistry = {
    new MetricsRegistry()
  }

}
