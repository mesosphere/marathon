package mesosphere.chaos.metrics

import java.lang.management.ManagementFactory

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jersey.InstrumentedResourceMethodDispatchAdapter
import com.codahale.metrics.jetty9.InstrumentedHandler
import com.codahale.metrics.jvm.{ BufferPoolMetricSet, GarbageCollectorMetricSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet }
import com.google.inject.{ AbstractModule, Provides, Singleton }
import org.eclipse.jetty.servlet.ServletContextHandler

class MetricsModule extends AbstractModule {

  override def configure(): Unit = {}

  @Singleton
  @Provides
  def provideRegistry(): MetricRegistry = {
    val registry = new MetricRegistry
    registry.register("jvm.gc", new GarbageCollectorMetricSet())
    registry.register("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer))
    registry.register("jvm.memory", new MemoryUsageGaugeSet())
    registry.register("jvm.threads", new ThreadStatesGaugeSet())
    registry
  }

  @Singleton
  @Provides
  def provideInstrumentedResourceMethodDispatchAdapter(registry: MetricRegistry) =
    new InstrumentedResourceMethodDispatchAdapter(registry)

  @Singleton
  @Provides
  def provideInstrumentedHandler(servletHandler: ServletContextHandler, registry: MetricRegistry) = {
    val handler = new InstrumentedHandler(registry)
    handler.setHandler(servletHandler)
    handler
  }

}
