package mesosphere.chaos.http

import com.codahale.metrics.servlets.MetricsServlet
import com.google.inject.{ Singleton, Provides, Scopes }
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider
import com.fasterxml.jackson.databind.{ Module, ObjectMapper }
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import javax.validation.Validation
import com.google.inject.servlet.ServletModule
import mesosphere.chaos.validation.{ JacksonMessageBodyProvider, ConstraintViolationExceptionMapper }
import javax.inject.Named
import mesosphere.chaos.ServiceStatus
import scala.collection.JavaConverters._
import com.google.inject.name.Names

/**
  * Base class for REST modules.
  */

class RestModule extends ServletModule {

  // Override these in a subclass to mount resources at a different path
  val pingUrl = "/ping"
  val metricsUrl = "/metrics"
  val loggingUrl = "/logging"
  val helpUrl = "/help"
  val guiceContainerUrl = "/*"
  val statusUrl = "/status"
  val statusCatchAllUrl = "/status/*"

  // Override this if you want to add your own modules
  val jacksonModules: Iterable[Module] = Seq(DefaultScalaModule)

  protected override def configureServlets(): Unit = {
    bind(classOf[PingServlet]).in(Scopes.SINGLETON)
    bind(classOf[MetricsServlet]).in(Scopes.SINGLETON)
    bind(classOf[LogConfigServlet]).in(Scopes.SINGLETON)
    bind(classOf[HelpServlet]).in(Scopes.SINGLETON)
    bind(classOf[ConstraintViolationExceptionMapper]).in(Scopes.SINGLETON)
    bind(classOf[ServiceStatus]).asEagerSingleton()
    bind(classOf[ServiceStatusServlet]).in(Scopes.SINGLETON)

    bind(classOf[String]).annotatedWith(Names.named("helpPathPrefix")).toInstance(helpUrl)

    serve(statusUrl).`with`(classOf[ServiceStatusServlet])
    serve(statusCatchAllUrl).`with`(classOf[ServiceStatusServlet])
    serve(pingUrl).`with`(classOf[PingServlet])
    serve(metricsUrl).`with`(classOf[MetricsServlet])
    serve(loggingUrl).`with`(classOf[LogConfigServlet])
    serve(helpUrl + "*").`with`(classOf[HelpServlet])
    serve(guiceContainerUrl).`with`(classOf[GuiceContainer])
  }

  @Provides
  @Singleton
  def provideJacksonJsonProvider(@Named("restMapper") mapper: ObjectMapper): JacksonJsonProvider = {
    new JacksonMessageBodyProvider(mapper, Validation.buildDefaultValidatorFactory().getValidator)
  }

  @Provides
  @Singleton
  @Named("restMapper")
  def provideRestMapper(): ObjectMapper = {
    val mapper = new ObjectMapper()
    mapper.registerModules(jacksonModules.asJava)
    mapper
  }
}
