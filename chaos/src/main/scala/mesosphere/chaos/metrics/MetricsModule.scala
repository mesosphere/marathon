package mesosphere.chaos.metrics

import java.lang.management.ManagementFactory

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jersey.InstrumentedResourceMethodDispatchAdapter
import com.codahale.metrics.jetty9.InstrumentedHandler
import com.codahale.metrics.jvm.{ BufferPoolMetricSet, GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet }
import javax.servlet.ServletContextListener
import mesosphere.chaos.http.ChaosServletConfig
import org.eclipse.jetty.server.handler.{ HandlerCollection, RequestLogHandler }
import org.eclipse.jetty.servlet.ServletContextHandler

class MetricsModule(
    servletContextHandler: ServletContextHandler,
    handlerCollection: HandlerCollection,
    requestLogHandler: RequestLogHandler) {

  val registry: MetricRegistry = {
    val registry = new MetricRegistry
    registry.register("jvm.gc", new GarbageCollectorMetricSet())
    registry.register("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer))
    registry.register("jvm.memory", new MemoryUsageGaugeSet())
    registry.register("jvm.threads", new ThreadStatesGaugeSet())
    registry
  }
  val chaosServletConfig: ServletContextListener = new ChaosServletConfig(registry)

  lazy val instrumentedResourceMethodDispatchAdapter =
    new InstrumentedResourceMethodDispatchAdapter(registry)

  val instrumentedHandler = {
    val handler = new InstrumentedHandler(registry)
    handler.setHandler(servletContextHandler)
    handler
  }

  servletContextHandler.addEventListener(chaosServletConfig)
  handlerCollection.setHandlers(Array(instrumentedHandler, requestLogHandler))
}
