package mesosphere.marathon.metrics

import com.google.inject.{ AbstractModule, Provides, Scopes, Singleton }
import org.apache.hadoop.metrics.util.MetricsRegistry

class MetricsReporterModule(metricsReporterConf: MetricsReporterConf) extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[MetricsReporterConf]).toInstance(metricsReporterConf)
    bind(classOf[MetricsReporterService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideMetricsRegistry(): MetricsRegistry = {
    new MetricsRegistry()
  }

}
