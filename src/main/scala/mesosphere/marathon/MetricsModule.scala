package mesosphere.marathon

import akka.Done
import akka.actor.ActorRefFactory
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.Config
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import mesosphere.marathon.metrics.current.DropwizardMetricsModule
import mesosphere.marathon.metrics.deprecated.KamonMetricsModule
import mesosphere.marathon.metrics.{Metrics, MetricsConf}
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.servlet.ServletContextHandler

trait MetricsModule {
  val metrics: Metrics
  val servletHandlers: Seq[Handler]

  def instrumentedHandlerFor(servletContextHandler: ServletContextHandler): Handler
  def registerServletInitializer(servletContextHandler: ServletContextHandler): Unit

  def snapshot(): Either[TickMetricSnapshot, MetricRegistry]
  def start(actorRefFactory: ActorRefFactory): Done
}

object MetricsModule {
  def apply(cliConf: MetricsConf with FeaturesConf, config: Config): MetricsModule = {
    if (cliConf.isDeprecatedFeatureEnabled(DeprecatedFeatures.kamonMetrics))
      new KamonMetricsModule(cliConf, config)
    else
      new DropwizardMetricsModule(cliConf)
  }
}
