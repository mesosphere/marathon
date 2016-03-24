package mesosphere.marathon.integration.setup

import javax.inject.{ Inject, Named }
import javax.ws.rs.core.Response
import javax.ws.rs.{ GET, Path }

import akka.actor.ActorRef
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

        override def subscribe(self: ActorRef): Unit = ???
        override def unsubscribe(self: ActorRef): Unit = ???
      }

      bind(classOf[LeaderInfo]).toInstance(leaderInfo)
    }
  }

  class ForwarderAppModule(myHostPort: String, httpConf: HttpConf, leaderProxyConf: LeaderProxyConf) extends BaseRestModule {
    @Named(ModuleNames.HOST_PORT)
    @Provides
    @Singleton
    def provideHostPort(httpConf: HttpConf): String = myHostPort

    override def configureServlets(): Unit = {
      super.configureServlets()

      bind(classOf[HttpConf]).toInstance(httpConf)
      bind(classOf[LeaderProxyConf]).toInstance(leaderProxyConf)
      bind(classOf[PingResource]).in(Scopes.SINGLETON)

      install(new LeaderProxyFilterModule)
    }
  }

  class ForwarderConf(args: Seq[String]) extends ScallopConf(args) with HttpConf with LeaderProxyConf

  def startHelloAppProcess(args: String*): Unit = {
    val conf = createConf(args: _*)

    ProcessKeeper.startJavaProcess(
      s"app_${conf.httpPort()}",
      heapInMegs = 128,
      arguments = List(ForwarderService.className, "helloApp") ++ args,
      upWhen = _.contains("Started ServerConnector"))
  }

  def startForwarderProcess(forwardToPort: Int, args: String*): Unit = {
    val conf = createConf(args: _*)

    ProcessKeeper.startJavaProcess(
      s"forwarder_${conf.httpPort()}",
      heapInMegs = 128,
      arguments = List(ForwarderService.className, "forwarder", forwardToPort.toString) ++ args,
      upWhen = _.contains("ServerConnector@"))
  }

  def main(args: Array[String]) {
    val service = args(0) match {
      case "helloApp" =>
        createHelloApp(args.tail: _*)
      case "forwarder" =>
        createForwarder(forwardToPort = args(1).toInt, args.drop(2): _*)
    }
    service.startAsync().awaitRunning()
    service.awaitTerminated()
  }

  def createHelloApp(args: String*): Service = {
    val conf = createConf(args: _*)
    log.info(s"Start hello app at ${conf.httpPort()}")
    startImpl(conf, new LeaderInfoModule(elected = true, leaderHostPort = None))
  }

  def createForwarder(forwardToPort: Int, args: String*): Service = {
    val conf = createConf(args: _*)
    log.info(s"Start forwarder on port  ${conf.httpPort()}, forwarding to $forwardToPort")
    startImpl(conf, new LeaderInfoModule(elected = false, leaderHostPort = Some(s"localhost:$forwardToPort")))
  }

  private[this] def createConf(args: String*): ForwarderConf = {
    val conf = new ForwarderConf(Array[String]("--assets_path", "/tmp") ++ args.map(_.toString))
    conf.afterInit()
    conf
  }

  private def startImpl(conf: ForwarderConf, leaderModule: Module, assetPath: String = "/tmp"): Service = {
    val injector = Guice.createInjector(
      new MetricsModule, new HttpModule(conf),
      new ForwarderAppModule(
        myHostPort = if (conf.disableHttp()) s"localhost:${conf.httpsPort()}" else s"localhost:${conf.httpPort()}",
        conf, conf),
      leaderModule
    )
    val http = injector.getInstance(classOf[HttpService])
    http
  }

}
