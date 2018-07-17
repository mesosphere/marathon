package mesosphere.marathon
package metrics.dummy

import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import akka.Done
import akka.actor.ActorRefFactory
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.metrics.Metrics
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.servlet.ServletContextHandler

class DummyMetricsModule extends MetricsModule {
  override val metrics: Metrics = DummyMetrics
  override val servletHandlers: Seq[Handler] = Seq.empty

  override def instrumentedHandlerFor(servletContextHandler: ServletContextHandler): Handler = servletContextHandler
  override def registerServletInitializer(servletContextHandler: ServletContextHandler): Unit = ()

  override def snapshot(): Either[TickMetricSnapshot, MetricRegistry] = Right(new MetricRegistry)
  override def start(actorRefFactory: ActorRefFactory): Done = Done
}
