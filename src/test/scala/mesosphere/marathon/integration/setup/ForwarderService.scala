package mesosphere.marathon
package integration.setup

import java.util.UUID
import javax.inject.{ Inject, Named }
import javax.ws.rs.core.Response
import javax.ws.rs.{ GET, Path }

import akka.Done
import akka.actor.ActorRef
import com.google.common.util.concurrent.Service
import com.google.inject._
import kamon.Kamon
import mesosphere.chaos.http.{ HttpConf, HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService }
import mesosphere.marathon.util.Lock
import mesosphere.util.{ CallerThreadExecutionContext, PortAllocator }
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ Future, Promise }
import scala.sys.process.{ Process, ProcessLogger }

/**
  * Helper that starts/stops the forwarder classes as java processes specifically for the integration test
  * Basically, the tests need to bring up a minimum version of the http service with leader forwarding enabled.
  */
class ForwarderService {
  private val children = Lock(ArrayBuffer.empty[Process])
  private val uuids = Lock(ArrayBuffer.empty[String])

  val logger = LoggerFactory.getLogger(classOf[ForwarderService])
  def close(): Unit = {
    children(_.par.foreach(_.destroy()))
    children(_.clear())
    uuids(_.foreach { id =>
      val PIDRE = """^\s*(\d+)\s+(\S*)\s*(.*)$""".r

      val pids = Process("jps -lv").!!.split("\n").collect {
        case PIDRE(pid, mainClass, jvmArgs) if mainClass.contains(classOf[ForwarderService].getName) && jvmArgs.contains(id) => pid
      }
      if (pids.nonEmpty) {
        Process(s"kill -9 ${pids.mkString(" ")}").!
      }
    })
    uuids(_.clear())
  }

  def startHelloApp(httpArg: String = "--http_port", args: Seq[String] = Nil): Future[Int] = {
    val port = PortAllocator.ephemeralPort()
    start(Nil, Seq("helloApp", httpArg, port.toString) ++ args).map(_ => port)(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  def startForwarder(forwardTo: Int, httpArg: String = "--http_port", trustStorePath: Option[String] = None,
    args: Seq[String] = Nil): Future[Int] = {
    val port = PortAllocator.ephemeralPort()
    val trustStoreArgs = trustStorePath.map { p => List(s"-Djavax.net.ssl.trustStore=$p") }.getOrElse(List.empty)
    start(trustStoreArgs, Seq("forwarder", forwardTo.toString, httpArg, port.toString) ++ args).map(_ => port)(CallerThreadExecutionContext.callerThreadExecutionContext)
  }

  private def start(trustStore: Seq[String] = Nil, args: Seq[String] = Nil): Future[Done] = {
    val java = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val cp = sys.props.getOrElse("java.class.path", "target/classes")
    val uuid = UUID.randomUUID().toString
    uuids(_ += uuid)
    val cmd = Seq(java, "-Xms256m", "-XX:+UseConcMarkSweepGC", "-XX:ConcGCThreads=2",
      // lower the memory pressure by limiting threads.
      "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
      "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
      "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
      "-Dscala.concurrent.context.minThreads=2",
      "-Dscala.concurrent.context.maxThreads=32",
      s"-DforwarderUuid:$uuid", "-classpath", cp, "-Xmx256M", "-client") ++ trustStore ++ Seq("mesosphere.marathon.integration.setup.ForwarderService") ++ args
    val up = Promise[Done]()
    val log = new ProcessLogger {
      def checkUp(s: String) = {
        logger.info(s)
        if (!up.isCompleted && s.contains("ServerConnector@")) {
          up.trySuccess(Done)
        }
      }
      override def out(s: => String): Unit = checkUp(s)

      override def err(s: => String): Unit = checkUp(s)

      override def buffer[T](f: => T): T = f
    }
    val process = Process(cmd).run(log)
    children(_ += process)
    up.future
  }
}

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
      val leader = leaderHostPort
      val electionService = new ElectionService {
        override def isLeader: Boolean = elected
        override def leaderHostPort: Option[String] = leader
        override def localHostPort: String = ???

        def offerLeadership(candidate: ElectionCandidate): Unit = ???
        def abdicateLeadership(): Unit = ???

        override def subscribe(self: ActorRef): Unit = ???
        override def unsubscribe(self: ActorRef): Unit = ???
      }

      bind(classOf[ElectionService]).toInstance(electionService)
    }
  }

  class ForwarderAppModule(myHostPort: String, httpConf: HttpConf, leaderProxyConf: LeaderProxyConf) extends BaseRestModule {
    @Named(ModuleNames.HOST_PORT)
    @Provides
    @Singleton
    def provideHostPort(): String = myHostPort

    override def configureServlets(): Unit = {
      super.configureServlets()

      bind(classOf[HttpConf]).toInstance(httpConf)
      bind(classOf[LeaderProxyConf]).toInstance(leaderProxyConf)
      bind(classOf[PingResource]).in(Scopes.SINGLETON)

      install(new LeaderProxyFilterModule)
    }
  }

  class ForwarderConf(args: Seq[String]) extends ScallopConf(args) with HttpConf with LeaderProxyConf

  def main(args: Array[String]): Unit = {
    Kamon.start()
    val service = args(0) match {
      case "helloApp" =>
        createHelloApp(args.tail: _*)
      case "forwarder" =>
        createForwarder(forwardToPort = args(1).toInt, args.drop(2): _*)
    }
    service.startAsync().awaitRunning()
    service.awaitTerminated()
  }

  private def createHelloApp(args: String*): Service = {
    val conf = createConf(args: _*)
    log.info(s"Start hello app at ${conf.httpPort()}")
    startImpl(conf, new LeaderInfoModule(elected = true, leaderHostPort = None))
  }

  private def createForwarder(forwardToPort: Int, args: String*): Service = {
    val conf = createConf(args: _*)
    log.info(s"Start forwarder on port  ${conf.httpPort()}, forwarding to $forwardToPort")
    startImpl(conf, new LeaderInfoModule(elected = false, leaderHostPort = Some(s"localhost:$forwardToPort")))
  }

  private def createConf(args: String*): ForwarderConf = {
    new ForwarderConf(Seq("--assets_path", "/tmp") ++ args) {
      verify()
    }
  }

  private def startImpl(conf: ForwarderConf, leaderModule: Module): Service = {
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
