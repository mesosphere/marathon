package mesosphere.marathon

import mesosphere.chaos.App
import org.rogach.scallop.ScallopConf
import mesosphere.chaos.http.{ HttpService, HttpModule, HttpConf }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.chaos.AppConfiguration
import mesosphere.marathon.event.{ EventModule, EventConfiguration }
import mesosphere.marathon.event.http.{ HttpEventModule, HttpEventConfiguration }
import com.google.inject.AbstractModule
import com.twitter.common.quantity.{ Time, Amount }
import com.twitter.common.zookeeper.ZooKeeperClient
import scala.collection.JavaConverters._
import java.util.Properties
import org.apache.log4j.Logger

object Main extends App {
  val log = Logger.getLogger(getClass.getName)
  log.info(s"Starting Marathon ${BuildInfo.version}")

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

  def modules(): Seq[AbstractModule] = {
    Seq(
      new HttpModule(conf) {
        // burst browser cache for assets
        protected override val resourceCacheControlHeader = Some("max-age=0, must-revalidate")
      },
      new MetricsModule,
      new MarathonModule(conf, conf, zk),
      new MarathonRestModule,
      new EventModule(conf)
    ) ++ getEventsModule
  }

  def getEventsModule: Option[AbstractModule] = {
    conf.eventSubscriber.get flatMap {
      case "http_callback" =>
        log.info("Using HttpCallbackEventSubscriber for event" +
          "notification")
        Some(new HttpEventModule())

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

  lazy val conf = new AllConf

  run(
    classOf[HttpService],
    classOf[MarathonSchedulerService]
  )
}
