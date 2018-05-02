package mesosphere.marathon
package api

import com.sun.jersey.spi.container.servlet.ServletContainer
import java.util.EnumSet
import javax.servlet.{ DispatcherType, Filter, Servlet }
import org.eclipse.jetty.servlet.{ FilterHolder, ServletContextHandler, ServletHolder }
import org.eclipse.jetty.servlets.EventSourceServlet

object HttpBindings {
  def apply(
    handler: ServletContextHandler,
    systemResource: SystemResource,
    rootApplication: RootApplication,
    leaderProxyFilter: LeaderProxyFilter,
    limitConcurrentRequestsFilter: LimitConcurrentRequestsFilter,
    corsFilter: CORSFilter,
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
    addFilter("/*", cacheDisablingFilter)
    addServlet("/v2/events", eventSourceServlet)

    for { p <- Seq("/ui", "/ui/*", "/help", "/api-console", "/api-console/*") } {
      addServlet(p, webJarServlet)
    }

    addServlet("/public/*", publicServlet)
    addServlet("/*", new ServletContainer(rootApplication))
  }
}
