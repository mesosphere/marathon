package mesosphere.marathon
package integration.setup

import java.net.BindException
import java.util.UUID

import javax.servlet.DispatcherType
import javax.ws.rs.core.{ Application, Response }
import javax.ws.rs.{ GET, Path }
import akka.Done
import akka.actor.ActorRef
import com.google.common.util.concurrent.Service
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import mesosphere.marathon.HttpConf
import mesosphere.marathon.api.HttpModule
import mesosphere.marathon.api._
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService }
import mesosphere.marathon.util.Lock
import mesosphere.util.PortAllocator
import org.eclipse.jetty.servlet.{ FilterHolder, ServletHolder }
import com.sun.jersey.spi.container.servlet.ServletContainer
import org.rogach.scallop.ScallopConf

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ Future, Promise }
import scala.sys.process.{ Process, ProcessLogger }

/**
  * Helper that starts/stops the forwarder classes as java processes specifically for the integration test
  * Basically, the tests need to bring up a minimum version of the http service with leader forwarding enabled.
  */
class ForwarderService extends StrictLogging {
  private val children = Lock(ArrayBuffer.empty[Process])
  private val uuids = Lock(ArrayBuffer.empty[String])

  def close(): Unit = {
    children(_.par.foreach(_.destroy()))
    children(_.clear())
    uuids(_.foreach { id =>
      val PIDRE = """^\s*(\d+)\s+(\S*)\s*(.*)$""".r

      val pids = Process("jps -lv").!!.split("\n").collect {
        case PIDRE(pid, mainClass, jvmArgs) if mainClass.contains(classOf[ForwarderService].getName) && jvmArgs.contains(id) => pid
      }
      if (pids.nonEmpty) {
        Process(s"kill ${pids.mkString(" ")}").!
      }
    })
    uuids(_.clear())
  }

  def startHelloApp(httpArg: String = "--http_port", args: Seq[String] = Nil): Future[Int] = {
    val port = PortAllocator.ephemeralPort()
    start(Nil, Seq("helloApp", httpArg, port.toString) ++ args).map(_ => port)(ExecutionContexts.callerThread)
  }

  def startForwarder(forwardTo: Int, httpArg: String = "--http_port", trustStorePath: Option[String] = None,
    args: Seq[String] = Nil): Future[Int] = {
    val port = PortAllocator.ephemeralPort()
    val trustStoreArgs = trustStorePath.map { p => List(s"-Djavax.net.ssl.trustStore=$p") }.getOrElse(List.empty)
    start(trustStoreArgs, Seq("forwarder", forwardTo.toString, httpArg, port.toString) ++ args).map(_ => port)(ExecutionContexts.callerThread)
  }

  private def start(trustStore: Seq[String], args: Seq[String]): Future[Done] = {
    logger.info(s"Starting forwarder '${args.mkString(" ")}'")
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
        if (!up.isCompleted) {
          if (s.contains("Started ServerConnector@")) {
            up.trySuccess(Done)
          } else if (s.contains("java.net.BindException: Address already in use")) {
            up.tryFailure(new BindException("Address already in use"))
          }
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

object ForwarderService extends StrictLogging {
  val className = {
    val withDollar = getClass.getName
    withDollar.substring(0, withDollar.length - 1)
  }

  @Path("/hello")
  class HelloResource() {
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

        override def subscribe(self: ActorRef): Unit = ???
        override def unsubscribe(self: ActorRef): Unit = ???
        override def leadershipTransitionEvents = ???
      }
    }
  }

  class ForwarderConf(args: Seq[String]) extends ScallopConf(args) with HttpConf with LeaderProxyConf

  def main(args: Array[String]): Unit = {
    Kamon.start()
    val service = args.toList match {
      case "helloApp" :: tail =>
        createHelloApp(tail: _*)
      case "forwarder" :: port :: tail =>
        createForwarder(forwardToPort = port.toInt, tail: _*)
      case args => throw new IllegalArgumentException(s"Unexpected forwarder args: $args")
    }
    service.startAsync().awaitRunning()
    service.awaitTerminated()
  }

  private def createHelloApp(args: String*): Service = {
    val conf = createConf(args: _*)
    logger.info(s"Start hello app at ${conf.httpPort()}")
    startImpl(conf, new LeaderInfoModule(elected = true, leaderHostPort = None))
  }

  private def createForwarder(forwardToPort: Int, args: String*): Service = {
    val conf = createConf(args: _*)
    logger.info(s"Start forwarder on port ${conf.httpPort()}, forwarding to $forwardToPort")
    startImpl(conf, new LeaderInfoModule(elected = false, leaderHostPort = Some(s"localhost:$forwardToPort")))
  }

  private def createConf(args: String*): ForwarderConf = {
    new ForwarderConf(Seq("--assets_path", "/tmp") ++ args) {
      verify()
    }
  }

  private def startImpl(conf: ForwarderConf, leaderModule: LeaderInfoModule): Service = {
    val myHostPort = if (conf.disableHttp()) s"localhost:${conf.httpsPort()}" else s"localhost:${conf.httpPort()}"

    val leaderProxyFilterModule = new LeaderProxyFilterModule()

    val filter = new LeaderProxyFilter(
      httpConf = conf,
      electionService = leaderModule.electionService,
      myHostPort = myHostPort,
      forwarder = leaderProxyFilterModule.provideRequestForwarder(
        httpConf = conf,
        leaderProxyConf = conf,
        myHostPort = myHostPort)
    )

    val application = new Application {
      override def getSingletons(): java.util.Set[Object] = {
        val s = new java.util.HashSet[Object]
        s.add(new HelloResource)
        s
      }
      override def toString(): String = "helloWorld"
    }
    val httpModule = new HttpModule(conf)
    httpModule.handler.addFilter(new FilterHolder(filter), "/*", java.util.EnumSet.allOf(classOf[DispatcherType]))
    httpModule.handler.addServlet(new ServletHolder(new PingServlet()), "/ping")
    httpModule.handler.addServlet(new ServletHolder(new ServletContainer(application)), "/*")
    httpModule.handlerCollection.addHandler(httpModule.handler)
    httpModule.httpService
  }

}
