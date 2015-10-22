package mesosphere.marathon.api

import com.codahale.metrics.servlets.MetricsServlet
import com.google.inject.Scopes
import com.google.inject.name.Names
import com.google.inject.servlet.ServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import mesosphere.chaos.ServiceStatus
import mesosphere.chaos.http.{ HelpServlet, LogConfigServlet, PingServlet, ServiceStatusServlet }

/**
  * This mostly corresponds to the RestModule from Chaos but with the following functionality removed:
  *
  * * Jackson serialization
  * * ConstraintViolationExceptionMapper (which depends on Jackson serialization)
  */
class BaseRestModule extends ServletModule {

  // Override these in a subclass to mount resources at a different path
  val pingUrl = "/ping"
  val metricsUrl = "/metrics"
  val loggingUrl = "/logging"
  val helpUrl = "/help"
  val guiceContainerUrl = "/*"
  val statusUrl = "/status"
  val statusCatchAllUrl = "/status/*"

  protected override def configureServlets() {
    bind(classOf[PingServlet]).in(Scopes.SINGLETON)
    bind(classOf[MetricsServlet]).in(Scopes.SINGLETON)
    bind(classOf[LogConfigServlet]).in(Scopes.SINGLETON)
    bind(classOf[HelpServlet]).in(Scopes.SINGLETON)
    bind(classOf[ServiceStatus]).asEagerSingleton()
    bind(classOf[ServiceStatusServlet]).in(Scopes.SINGLETON)

    bind(classOf[String]).annotatedWith(Names.named("helpPathPrefix")).toInstance(helpUrl)

    serve(statusUrl).`with`(classOf[ServiceStatusServlet])
    serve(statusCatchAllUrl).`with`(classOf[ServiceStatusServlet])
    serve(pingUrl).`with`(classOf[PingServlet])
    serve(metricsUrl).`with`(classOf[MetricsServlet])
    serve(loggingUrl).`with`(classOf[LogConfigServlet])
    serve(guiceContainerUrl).`with`(classOf[GuiceContainer])
  }
}
