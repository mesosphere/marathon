package mesosphere.marathon

import com.google.inject.{ AbstractModule, Provides, Singleton }

class MarathonMetricsModule extends AbstractModule {
  override def configure(): Unit = {}

  @Singleton
  @Provides
  def provideHttpTransferMetrics(): api.HttpTransferMetrics =
    new api.HTTPMetricsFilter()

  @Singleton
  @Provides
  def provideHttpTransferMetricsHandler(httpTransferMetrics: api.HttpTransferMetrics): api.HttpTransferMetricsHandler =
    new api.HttpTransferMetricsHandler(httpTransferMetrics)
}
