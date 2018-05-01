package mesosphere.chaos.http

import com.codahale.metrics.MetricRegistry
import javax.servlet.{ ServletContextEvent, ServletContextListener }
import com.codahale.metrics.servlets.MetricsServlet

//TODO(FL|TK): Allow passing in doWithContext closure to remove coupling to Registry.
class ChaosServletConfig(val metricsRegistry: MetricRegistry) extends ServletContextListener {

  override def contextInitialized(servletContextEvent: ServletContextEvent): Unit = {
    servletContextEvent.getServletContext.setAttribute(MetricsServlet.METRICS_REGISTRY, metricsRegistry)
  }

  override def contextDestroyed(servletContextEvent: ServletContextEvent): Unit = {
  }
}
