package mesosphere.marathon

import com.google.inject.Module
import com.twitter.common.quantity.{ Amount, Time }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.chaos.App
import mesosphere.chaos.http.{ HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.event.EventModule
import mesosphere.marathon.event.http.HttpEventModule
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class MarathonApp extends App {
  val log = LoggerFactory.getLogger(getClass.getName)

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
      new HttpModule(conf),
      new MetricsModule,
      new MarathonModule(conf, conf, zk),
      new MarathonRestModule,
      new EventModule(conf),
      new DebugModule(conf),
      new CoreGuiceModule
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

  override lazy val conf = new AllConf(args)

  def runDefault(): Unit = {
    log.info(s"Starting Marathon ${BuildInfo.version} with ${args.mkString(" ")}")
    run(
      classOf[HttpService],
      classOf[MarathonSchedulerService]
    )
  }
}

object Main extends MarathonApp {
  runDefault()
}
