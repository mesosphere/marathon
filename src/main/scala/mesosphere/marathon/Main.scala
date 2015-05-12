package mesosphere.marathon

import com.google.inject.Module
import com.twitter.common.quantity.{ Amount, Time }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.chaos.{ App, AppConfiguration }
import mesosphere.chaos.http.{ HttpConf, HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.event.http.{ HttpEventConfiguration, HttpEventModule }
import mesosphere.marathon.event.{ EventConfiguration, EventModule }
import org.apache.log4j.Logger
import org.rogach.scallop.ScallopConf

import scala.collection.JavaConverters._

class MarathonApp extends App {
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

  override lazy val conf = new AllConf

  def runDefault(): Unit = {
    log.info(s"Starting Marathon ${BuildInfo.version}")
    run(
      classOf[HttpService],
      classOf[MarathonSchedulerService]
    )
  }
}

object Main extends MarathonApp {
  runDefault()
}