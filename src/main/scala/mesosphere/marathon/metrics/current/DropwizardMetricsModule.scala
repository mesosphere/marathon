package mesosphere.marathon
package metrics.current

import java.lang.management.ManagementFactory

import akka.Done
import akka.actor.ActorRefFactory
import com.codahale.metrics.MetricRegistry
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import mesosphere.marathon.metrics.current.reporters.{DataDogAPIReporter, DataDogUDPReporter, StatsDReporter}
import mesosphere.marathon.metrics.{Metrics, MetricsConf}
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.servlet.ServletContextHandler

class DropwizardMetricsModule(val metricsConf: MetricsConf) extends MetricsModule {
  override lazy val servletHandlers: Seq[Handler] = Seq(
    new api.HttpTransferMetricsHandler(new api.HTTPMetricsFilter(metrics)))

  private lazy val metricNamePrefix = metricsConf.metricsNamePrefix()
  private lazy val registry: MetricRegistry = {
    val r = new MetricRegistry
    r.register(
      s"$metricNamePrefix.jvm.buffers",
      new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer))
    r.register(s"$metricNamePrefix.jvm.gc", new GarbageCollectorMetricSet())
    r.register(s"$metricNamePrefix.jvm.memory", new MemoryUsageGaugeSet())
    r.register(s"$metricNamePrefix.jvm.threads", new ThreadStatesGaugeSet())
    r
  }
  override lazy val metrics: Metrics = new DropwizardMetrics(metricsConf, registry)

  override def instrumentedHandlerFor(servletContextHandler: ServletContextHandler): Handler = {
    val handler = new InstrumentedHandler(registry, metricNamePrefix)
    handler.setHandler(servletContextHandler)
    handler
  }

  override def registerServletInitializer(servletContextHandler: ServletContextHandler): Unit = ()

  override def snapshot(): Either[TickMetricSnapshot, MetricRegistry] = Right(registry)
  override def start(actorRefFactory: ActorRefFactory): Done = {
    if (metricsConf.metricsStatsDReporter())
      actorRefFactory.actorOf(StatsDReporter.props(metricsConf, registry), "StatsDReporter")
    if (metricsConf.metricsDadaDogReporter()) {
      if (metricsConf.metricsDataDogProtocol() == "udp")
        actorRefFactory.actorOf(DataDogUDPReporter.props(metricsConf, registry), name = "DataDogUDPReporter")
      if (metricsConf.metricsDataDogProtocol() == "api")
        actorRefFactory.actorOf(DataDogAPIReporter.props(metricsConf, registry), name = "DataDogAPIReporter")
    }
    Done
  }
}
