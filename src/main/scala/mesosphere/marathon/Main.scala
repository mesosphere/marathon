package mesosphere.marathon

import com.google.common.util.concurrent.ServiceManager
import com.google.inject.{ Guice, Module }
import com.twitter.common.quantity.{ Amount, Time }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.chaos.AppConfiguration
import mesosphere.chaos.http.{ HttpConf, HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.event.http.{ HttpEventConfiguration, HttpEventModule }
import mesosphere.marathon.event.{ EventConfiguration, EventModule }
import org.apache.log4j.Logger
import org.rogach.scallop.ScallopConf
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }

class MarathonApp extends App {
  // Handle java.util.logging with SLF4J, copied from Chaos's App trait
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()
  val log = Logger.getLogger(getClass.getName)

  lazy val zk: ZooKeeperClient = {
    require(
      conf.zooKeeperTimeout() < Integer.MAX_VALUE,
      "ZooKeeper timeout too large!"
    )

    val client = new ZooKeeperClient(
      Amount.of(conf.zooKeeperTimeout().toInt, Time.MILLISECONDS),
      conf.zooKeeperHostAddresses.asJavaCollection
    )

    // Marathon can't do anything useful without a ZK connection
    // so we wait to proceed until one is available
    var connectedToZk = false

    while (!connectedToZk) {
      try {
        log.info("Connecting to Zookeeper...")
        client.get
        connectedToZk = true
      }
      catch {
        case t: Throwable =>
          log.warn("Unable to connect to Zookeeper, retrying...")
      }
    }
    client
  }

  def modules(): Seq[Module] = {
    Seq(
      new HttpModule(conf) {
        // burst browser cache for assets
        protected override val resourceCacheControlHeader = Some("max-age=0, must-revalidate")
      },
      new MetricsModule,
      new MarathonModule(conf, conf, zk),
      new MarathonRestModule,
      new EventModule(conf),
      new DebugModule(conf)
    ) ++ getEventsModule
  }

  def getEventsModule: Option[Module] = {
    conf.eventSubscriber.get flatMap {
      case "http_callback" =>
        log.info("Using HttpCallbackEventSubscriber for event" +
          "notification")
        Some(new HttpEventModule(conf))

      case _ =>
        log.info("Event notification disabled.")
        None
    }
  }

  class AllConf extends ScallopConf(args)
    with HttpConf
    with MarathonConf
    with AppConfiguration
    with EventConfiguration
    with HttpEventConfiguration
    with DebugConf

  lazy val conf = new AllConf
  var serviceManager: Option[ServiceManager] = None

  def runDefault(): Unit = {
    log.info(s"Starting Marathon ${BuildInfo.version}")

    conf.afterInit()

    sys.addShutdownHook(shutdownAndWait())

    lazy val injector = Guice.createInjector(modules().asJava)
    val services = List(
      injector.getInstance(classOf[HttpService]),
      injector.getInstance(classOf[MarathonSchedulerService])
    )
    val serviceManager = new ServiceManager(services.asJava)
    this.serviceManager = Some(serviceManager)

    serviceManager.startAsync

    Try { serviceManager.awaitHealthy() } match {
      case Success(_) =>
        log.info("All services up and running.")
      case Failure(f) =>
        log.fatal(s"Failed to start all services. Services by state: ${serviceManager.servicesByState()}", f)
        shutdownAndWait()
        System.exit(1)
    }
  }

  private def shutdownAndWait(): Unit = {
    serviceManager.foreach(_.stopAsync())
    serviceManager.foreach(_.awaitStopped())
  }
}

object Main extends MarathonApp {
  runDefault()
}
