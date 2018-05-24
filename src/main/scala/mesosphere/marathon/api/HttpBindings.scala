package mesosphere.marathon
package api

import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer
import java.util.EnumSet
import javax.servlet.{DispatcherType, Filter, Servlet}
import org.eclipse.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}
import org.eclipse.jetty.servlets.EventSourceServlet

/**
  * Used to bind the Marathon root application (api routes, etc.), various filters and other servlets to the Jetty
  * instance.
  */
object HttpBindings {
  def apply(
    handler: ServletContextHandler,
    rootApplication: RootApplication,
    leaderProxyFilter: LeaderProxyFilter,
    limitConcurrentRequestsFilter: LimitConcurrentRequestsFilter,
    corsFilter: CORSFilter,
    httpMetricsFilter: HTTPMetricsFilter,
    cacheDisablingFilter: CacheDisablingFilter,
    eventSourceServlet: EventSourceServlet,
    webJarServlet: WebJarServlet,
    publicServlet: PublicServlet): Unit = {

    val allDispatchers = EnumSet.allOf(classOf[DispatcherType])
    def addFilter(path: String, filter: Filter, dispatches: EnumSet[DispatcherType] = allDispatchers): Unit = {
      handler.addFilter(new FilterHolder(filter), path, dispatches)
    }

    def addServlet(path: String, servlet: Servlet): Unit = {
      handler.addServlet(new ServletHolder(servlet), path)
    }

    addFilter("/*", leaderProxyFilter)
    addFilter("/*", limitConcurrentRequestsFilter)
    addFilter("/*", corsFilter)
    addFilter("/*", httpMetricsFilter)
    addFilter("/*", cacheDisablingFilter)
    addServlet("/v2/events", eventSourceServlet)

    for { p <- Seq("/ui", "/ui/*", "/help", "/api-console", "/api-console/*") } {
      addServlet(p, webJarServlet)
    }

    addServlet("/public/*", publicServlet)

    addServlet("/*", new ServletContainer(ResourceConfig.forApplication(rootApplication)))
  }
}
