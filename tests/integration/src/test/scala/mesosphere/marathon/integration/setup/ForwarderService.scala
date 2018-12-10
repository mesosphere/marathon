package mesosphere.marathon
package integration.setup

import java.util.concurrent.{ConcurrentLinkedQueue, Executor}

import javax.servlet.DispatcherType
import javax.ws.rs.core.{Context, HttpHeaders, MediaType, Response}
import javax.ws.rs.{GET, POST, Path, Produces}
import akka.Done
import akka.actor.ActorSystem
import com.google.common.util.concurrent.Service
import com.typesafe.scalalogging.StrictLogging

import mesosphere.marathon.api.forwarder.AsyncUrlConnectionRequestForwarder
import mesosphere.marathon.api.{HttpModule, RootApplication}
import mesosphere.marathon.api._
import mesosphere.marathon.core.election.{ElectionCandidate, ElectionService}
import mesosphere.marathon.io.SSLContextUtil
import mesosphere.marathon.metrics.dummy.DummyMetricsModule
import mesosphere.util.PortAllocator
import org.eclipse.jetty.servlet.{FilterHolder, ServletHolder}
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer
import org.rogach.scallop.ScallopConf
import play.api.libs.json.{JsObject, JsString}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Helper that starts/stops the forwarder classes as java processes specifically for the integration test
  * Basically, the tests need to bring up a minimum version of the http service with leader forwarding enabled.
  */
class ForwarderService extends StrictLogging {
  @volatile private var closed = false
  val children = new ConcurrentLinkedQueue[Service]

  def close(): Unit = {
    closed = true
    children.iterator().asScala.foreach(_.stopAsync())
    children.clear()
  }

  /**
    * Reference to a service running in this JVM
    *
    * @param port The port on which the service is listening
    * @param launched Future which completes when the service finishes launching
    * @param service Reference to the Service instance itself
    */
  case class AppInstance(port: Int, launched: Future[Done], service: Service)

  /** Call before launching service */
  private def launchService(service: Service): Future[Done] = {
    val sameThreadExecutor: Executor = new Executor {
      override def execute(r: Runnable): Unit = r.run()
    }

    val p = Promise[Done]
    service.addListener(new Service.Listener {
      override def failed(s: Service.State, t: Throwable): Unit = p.tryFailure(t)
      override def running(): Unit = p.trySuccess(Done)
    }, sameThreadExecutor)
    service.startAsync()
    p.future
  }

  private def launching(serviceFactory: Int => Service): AppInstance = {
    require(!closed)
    val port = PortAllocator.ephemeralPort()
    val service = serviceFactory(port)
    val isLaunched = launchService(service)
    val i = AppInstance(port, isLaunched, service)
    children.add(service)
    i
  }

  /**
    * Allocates an ephemeral port and starts dummy http service listening on it. Registers such that
    * forwarderService.close() will terminate this app.
    *
    * @return AppInstance referring to the app launched.
    */
  def startHelloApp(httpArg: String = "--http_port", args: Seq[String] = Nil)(
    implicit
    actorSystem: ActorSystem, executionContext: ExecutionContext): AppInstance = {
    launching { port =>
      ForwarderService.createHelloApp(Seq(httpArg, port.toString) ++ args)
    }
  }

  /**
    * Allocates an ephemeral port and starts simple forwarder service listening on it. Registers such that
    * forwarderService.close() will terminate this app.
    *
    * @param forwardTo The port to which this service should forward
    * @param httpArg Specify --https_port if using ssl
    * @param args Extra args to pass to the forwarder service
    *
    * @return AppInstance referring to the app launched.
    */
  def startForwarder(forwardTo: Int, httpArg: String = "--http_port", args: Seq[String] = Nil)(
    implicit
    actorSystem: ActorSystem, executionContext: ExecutionContext): AppInstance = {
    require(!closed)
    launching { port =>
      ForwarderService.createForwarder(forwardTo, Seq(httpArg, port.toString) ++ args)
    }
  }
}

object ForwarderService extends StrictLogging {
  val className = {
    val withDollar = getClass.getName
    withDollar.substring(0, withDollar.length - 1)
  }

  @Path("")
  class DummyForwarderResource() extends JaxResource {
    @GET
    @Path("/ping")
    def ping(): Response = {
      Response.ok().entity("pong\n").build()
    }

    @GET
    @Path("/hello")
    def index(): Response = {
      Response.ok().entity("Hi").build()
    }

    @GET
    @Path("/json")
    @Produces(Array(MediaType.APPLICATION_JSON))
    def json(): Response = {
      Response.ok.entity("{}").build()
    }

    @GET
    @Path("/hello/crash")
    def crash(): Response = {
      Response.serverError().entity("Error").build()
    }

    @GET
    @Path("/v2/events")
    def events(): Response = {
      Response.ok().entity("events").build()
    }

    @POST
    @Path("/headers")
    def headers(@Context headers: HttpHeaders): Response = {
      val headersPairs = headers.getRequestHeaders.entrySet().asScala.toSeq.map { e =>
        (e.getKey, JsString(e.getValue.toString))
      }
      val headersJson = JsObject(headersPairs)
      val body = headersJson.toString()
      Response.ok().entity(body).build()
    }
  }

  class LeaderInfoModule(elected: Boolean, leaderHostPort: Option[String]) {
    logger.info(s"Leader configuration: elected=$elected leaderHostPort=$leaderHostPort")

    lazy val electionService: ElectionService = {
      val leader = leaderHostPort
      new ElectionService {
        override def isLeader: Boolean = elected
        override def leaderHostPort: Option[String] = leader
        override def localHostPort: String = ???

        def offerLeadership(candidate: ElectionCandidate): Unit = ???
        def abdicateLeadership(): Unit = ???

        override def leadershipTransitionEvents = ???
      }
    }
  }

  class ForwarderConf(args: Seq[String]) extends ScallopConf(args) with HttpConf with LeaderProxyConf with FeaturesConf

  private[setup] def createHelloApp(args: Seq[String])(implicit actorSystem: ActorSystem, ec: ExecutionContext): Service = {
    val conf = createConf(args: _*)
    logger.info(s"Start hello app at ${conf.httpPort()}")
    startImpl(conf, new LeaderInfoModule(elected = true, leaderHostPort = None))
  }

  private[setup] def createForwarder(forwardToPort: Int, args: Seq[String])(implicit actorSystem: ActorSystem, ec: ExecutionContext): Service = {
    println(args.toList)
    val conf = createConf(args: _*)
    logger.info(s"Start forwarder on port ${conf.httpPort()}, forwarding to $forwardToPort")
    startImpl(conf, new LeaderInfoModule(elected = false, leaderHostPort = Some(s"localhost:$forwardToPort")))
  }

  private def createConf(args: String*): ForwarderConf = {
    new ForwarderConf(Seq("--assets_path", "/tmp") ++ args) {
      verify()
    }
  }

  private def startImpl(conf: ForwarderConf, leaderModule: LeaderInfoModule)(implicit actorSystem: ActorSystem, ec: ExecutionContext): Service = {
    val myHostPort = if (conf.disableHttp()) s"localhost:${conf.httpsPort()}" else s"localhost:${conf.httpPort()}"

    val sslContext = SSLContextUtil.createSSLContext(conf.sslKeystorePath.toOption, conf.sslKeystorePassword.toOption)
    val forwarder = new AsyncUrlConnectionRequestForwarder(sslContext, conf, myHostPort)

    val filter = new LeaderProxyFilter(
      disableHttp = conf.disableHttp(),
      electionService = leaderModule.electionService,
      myHostPort = myHostPort,
      forwarder = forwarder
    )

    val application = new RootApplication(Nil, Seq(new DummyForwarderResource))
    val httpModule = new HttpModule(conf, new DummyMetricsModule)
    httpModule.servletContextHandler.addFilter(new FilterHolder(filter), "/*", java.util.EnumSet.allOf(classOf[DispatcherType]))
    httpModule.servletContextHandler.addServlet(
      new ServletHolder(
        new ServletContainer(
          ResourceConfig.forApplication(application))), "/*")
    httpModule.marathonHttpService
  }
}
