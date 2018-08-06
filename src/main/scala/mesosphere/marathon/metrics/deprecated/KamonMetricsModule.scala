package mesosphere.marathon
package metrics.deprecated

import java.lang.management.ManagementFactory
import java.time.Duration

import akka.Done
import akka.actor.{Actor, ActorRefFactory, Props}
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jetty9.InstrumentedHandler
import com.codahale.metrics.jvm.{BufferPoolMetricSet, GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics.servlets.MetricsServlet
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import javax.servlet.{ServletContextEvent, ServletContextListener}
import kamon.Kamon
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.{Entity, SubscriptionFilter}
import kamon.util.MilliTimestamp
import mesosphere.marathon.metrics.{Metrics, MetricsConf}
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.servlet.ServletContextHandler

object AcceptAllFilter extends SubscriptionFilter {
  override def accept(entity: Entity): Boolean = true
}

class KamonMetricsModule(cliConf: MetricsConf, config: Config) extends MetricsModule with StrictLogging {
  override lazy val metrics: Metrics = KamonMetrics
  override lazy val servletHandlers: Seq[Handler] = Seq(
    new api.HttpTransferMetricsHandler(new api.HTTPMetricsFilter(metrics)),
    new api.HttpResponseMetricsHandler(metrics))

  private lazy val registry: MetricRegistry = {
    val registry = new MetricRegistry
    registry.register("jvm.gc", new GarbageCollectorMetricSet())
    registry.register("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer))
    registry.register("jvm.memory", new MemoryUsageGaugeSet())
    registry.register("jvm.threads", new ThreadStatesGaugeSet())
    registry
  }

  private object MetricsServletInitializer extends ServletContextListener {
    override def contextInitialized(servletContextEvent: ServletContextEvent): Unit = {
      servletContextEvent.getServletContext.setAttribute(MetricsServlet.METRICS_REGISTRY, registry)
    }

    override def contextDestroyed(servletContextEvent: ServletContextEvent): Unit = {
    }
  }

  override def instrumentedHandlerFor(servletContextHandler: ServletContextHandler): Handler = {
    val handler = new InstrumentedHandler(registry)
    handler.setHandler(servletContextHandler)
    handler
  }

  override def registerServletInitializer(servletContextHandler: ServletContextHandler): Unit = {
    servletContextHandler.addEventListener(MetricsServletInitializer)
  }

  private[this] var metricsSnapshot: TickMetricSnapshot = {
    val now = MilliTimestamp.now
    TickMetricSnapshot(now, now, Map.empty)
  }

  override def snapshot(): Either[TickMetricSnapshot, MetricRegistry] = Left(metricsSnapshot)

  // Starts collecting snapshots.
  override def start(actorRefFactory: ActorRefFactory): Done = {
    Kamon.start(config)

    class SubscriberActor() extends Actor {
      val slidingAverageSnapshot: SlidingAverageSnapshot = new SlidingAverageSnapshot(
        Duration.ofSeconds(cliConf.averagingWindowSizeSeconds.getOrElse(30L))
      )

      override def receive: Actor.Receive = {
        case snapshot: TickMetricSnapshot =>
          metricsSnapshot = slidingAverageSnapshot.updateWithTick(snapshot)
      }
    }
    Kamon.metrics.subscribe(AcceptAllFilter, actorRefFactory.actorOf(Props(new SubscriberActor)))
    Done
  }
}

