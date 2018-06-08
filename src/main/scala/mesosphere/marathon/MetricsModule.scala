package mesosphere.marathon

import com.codahale.metrics.servlets.MetricsServlet
import java.lang.management.ManagementFactory

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jetty9.InstrumentedHandler
import com.codahale.metrics.jvm.{BufferPoolMetricSet, GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import javax.servlet.{ServletContextEvent, ServletContextListener}
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.servlet.ServletContextHandler

class MetricsModule {
  val registry: MetricRegistry = {
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

  def instrumentedHandlerFor(servletContextHandler: ServletContextHandler): Handler = {
    val handler = new InstrumentedHandler(registry)
    handler.setHandler(servletContextHandler)
    handler
  }

  def registerServletInitializer(servletContextHandler: ServletContextHandler): Unit = {
    servletContextHandler.addEventListener(MetricsServletInitializer)
  }

  lazy val httpTransferMetrics: api.HttpTransferMetrics = new api.HTTPMetricsFilter()
  lazy val httpTransferMetricsHandler = new api.HttpTransferMetricsHandler(httpTransferMetrics)
}
