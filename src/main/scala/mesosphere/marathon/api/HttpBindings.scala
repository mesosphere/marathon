package mesosphere.marathon
package api

import com.google.inject.Injector
import com.sun.jersey.spi.container.servlet.ServletContainer
import java.util.EnumSet
import javax.servlet.{ DispatcherType, Filter, Servlet }
import javax.ws.rs.core.Application
import org.eclipse.jetty.server.handler.HandlerCollection
import org.eclipse.jetty.servlet.{ FilterHolder, ServletContextHandler, ServletHolder }
import org.eclipse.jetty.servlets.EventSourceServlet
import scala.reflect.ClassTag

class HttpBindings(
    injector: Injector,
    handler: ServletContextHandler,
    collection: HandlerCollection) {
  protected def inject[T](implicit classTag: ClassTag[T]): T = {
    injector.getInstance(classTag.runtimeClass.asInstanceOf[Class[T]])
  }

  protected def addFilter(path: String, filter: Filter, dispatches: EnumSet[DispatcherType] = EnumSet.allOf(classOf[DispatcherType])): Unit = {
    handler.addFilter(new FilterHolder(filter), path, dispatches)
  }

  protected def addServlet(path: String, servlet: Servlet): Unit = {
    handler.addServlet(new ServletHolder(servlet), path)
  }

  class MarathonApplication extends Application {
    override def getSingletons(): java.util.Set[Object] = {
      val singletons = new java.util.HashSet[Object]
      singletons.add(inject[SystemResource])

      singletons.add(inject[v2.AppsResource])
      singletons.add(inject[v2.PodsResource])
      singletons.add(inject[v2.TasksResource])
      singletons.add(inject[v2.QueueResource])
      singletons.add(inject[v2.GroupsResource])
      singletons.add(inject[v2.InfoResource])
      singletons.add(inject[v2.LeaderResource])
      singletons.add(inject[v2.DeploymentsResource])
      singletons.add(inject[v2.SchemaResource])
      singletons.add(inject[v2.PluginsResource])
      singletons
    }
  }

  val pingUrl = "/ping"
  val loggingUrl = "/logging"
  val guiceContainerUrl = "/*"
  val statusUrl = "/status"
  val statusCatchAllUrl = "/status/*"

  def apply(): Unit = {
    collection.addHandler(handler) // TODO this should probably be done outside of this method

    addFilter("/*", inject[LeaderProxyFilter])
    addFilter("/*", inject[LimitConcurrentRequestsFilter])
    addFilter("/*", inject[CORSFilter])
    addFilter("/*", inject[CacheDisablingFilter])
    addServlet("/v2/events", inject[EventSourceServlet])

    val webJarServlet = inject[WebJarServlet]
    for { p <- Seq("/", "/ui", "/ui/*", "/help", "/api-console", "/api-console/*") } {
      addServlet(p, webJarServlet)
    }

    addServlet("/public/*", inject[PublicServlet])
    addServlet(statusUrl, inject[ServiceStatusServlet])
    addServlet(statusCatchAllUrl, inject[ServiceStatusServlet])
    addServlet(pingUrl, inject[PingServlet])
    addServlet(loggingUrl, inject[LogConfigServlet])
    addServlet("/*", new ServletContainer(new MarathonApplication))
    println("lolol")
  }
}
