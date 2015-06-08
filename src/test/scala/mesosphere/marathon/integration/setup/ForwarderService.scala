package mesosphere.marathon.integration.setup

import javax.inject.{ Inject, Named }
import javax.ws.rs.core.Response
import javax.ws.rs.{ GET, Path }

import com.google.common.util.concurrent.Service
import com.google.inject._
import mesosphere.chaos.http.{ HttpConf, HttpModule, HttpService, RestModule }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api._
import mesosphere.marathon.{ LeaderProxyConf, ModuleNames }
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

object ForwarderService {
  private val log = LoggerFactory.getLogger(getClass)
  val className = {
    val withDollar = getClass.getName
    withDollar.substring(0, withDollar.length - 1)
  }

  @Path("hello")
  class PingResource @Inject() () {
    @GET
    def index(): Response = {
      Response.ok().entity("Hi").build()
    }

    @GET
    @Path("/crash")
    def crash(): Response = {
      Response.serverError().entity("Error").build()
    }
  }

  class LeaderInfoModule(elected: Boolean, leaderHostPort: Option[String]) extends AbstractModule {
    log.info(s"Leader configuration: elected=$elected leaderHostPort=$leaderHostPort")

    override def configure(): Unit = {
      val electedAlias = elected
      val leaderInfo = new LeaderInfo {
        override def elected: Boolean = electedAlias
        override def currentLeaderHostPort(): Option[String] = leaderHostPort
      }

      bind(classOf[LeaderInfo]).toInstance(leaderInfo)
    }
  }

  class ForwarderAppModule(myHostPort: String, httpConf: HttpConf, leaderProxyConf: LeaderProxyConf) extends RestModule {
    @Named(ModuleNames.NAMED_HOST_PORT)
    @Provides
    @Singleton
    def provideHostPort(httpConf: HttpConf): String = myHostPort

    override def configureServlets(): Unit = {
      super.configureServlets()

      bind(classOf[HttpConf]).toInstance(httpConf)
      bind(classOf[LeaderProxyConf]).toInstance(leaderProxyConf)
      bind(classOf[PingResource]).in(Scopes.SINGLETON)
      bind(classOf[RequestForwarder]).to(classOf[JavaUrlConnectionRequestForwarder]).asEagerSingleton()
      bind(classOf[LeaderProxyFilter]).asEagerSingleton()
      filter("/*").through(classOf[LeaderProxyFilter])
    }
  }

  def main(args: Array[String]) {
    val service = args(0) match {
      case "helloApp" =>
        createHelloApp(port = args(1).toInt)
      case "forwarder" =>
        createForwarder(port = args(1).toInt, forwardToPort = args(2).toInt)
    }
    service.startAsync().awaitRunning()
    service.awaitTerminated()
  }

  def startHelloAppProcess(port: Int): Unit = {
    ProcessKeeper.startJavaProcess(
      s"app_$port",
      arguments = List(ForwarderService.className, "helloApp", port.toString),
      upWhen = _.contains("Started SelectChannelConnector"))
  }

  def startForwarderProcess(port: Int, forwardToPort: Int): Unit = {
    ProcessKeeper.startJavaProcess(
      s"forwarder_$port",
      arguments = List(ForwarderService.className, "forwarder", port.toString, forwardToPort.toString),
      upWhen = _.contains("Started SelectChannelConnector"))
  }

  def createHelloApp(port: Int): Service = {
    log.info(s"Start hello app at $port")
    startImpl(port, new LeaderInfoModule(elected = true, leaderHostPort = None))
  }

  def createForwarder(port: Int, forwardToPort: Int): Service = {
    log.info(s"Start forwarder on port $port, forwarding to $forwardToPort")
    startImpl(port, new LeaderInfoModule(elected = false, leaderHostPort = Some(s"localhost:$forwardToPort")))
  }

  private def startImpl(port: Int, leaderModule: Module, assetPath: String = "/tmp"): Service = {
    val conf = new ScallopConf(Array(
      "--http_port", port.toString,
      "--assets_path", assetPath)) with HttpConf with LeaderProxyConf
    conf.afterInit()
    val injector = Guice.createInjector(
      new MetricsModule, new HttpModule(conf),
      new ForwarderAppModule(myHostPort = s"localhost:$port", conf, conf),
      leaderModule
    )
    val http = injector.getInstance(classOf[HttpService])
    http
  }

}