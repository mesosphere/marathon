package mesosphere.chaos.http

import com.google.inject.{ Injector, Inject }
import com.codahale.metrics.MetricRegistry
import com.google.inject.servlet.GuiceServletContextListener
import javax.servlet.ServletContextEvent
import com.codahale.metrics.servlets.MetricsServlet

//TODO(FL|TK): Allow passing in doWithContext closure to remove coupling to Registry.
class GuiceServletConfig @Inject() (val injector: Injector, val metricsRegistry: MetricRegistry) extends GuiceServletContextListener {

  override def contextInitialized(servletContextEvent: ServletContextEvent) {
    super.contextInitialized(servletContextEvent)
    servletContextEvent.getServletContext.setAttribute(MetricsServlet.METRICS_REGISTRY, metricsRegistry)
  }

  def getInjector = injector
}
