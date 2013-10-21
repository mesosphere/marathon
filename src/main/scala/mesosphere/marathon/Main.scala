package mesosphere.marathon

import mesosphere.chaos.App
import org.rogach.scallop.ScallopConf
import mesosphere.chaos.http.{HttpService, HttpModule, HttpConf}
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.marathon.api.MarathonRestModule
import mesosphere.chaos.AppConfiguration
import mesosphere.marathon.event.{EventModule, EventConfiguration}
import mesosphere.marathon.event.http.{HttpEventModule, HttpEventConfiguration}
import com.google.inject.AbstractModule
import java.util.logging.Logger

/**
 * @author Tobi Knaup
 */
object Main extends App {

  val VERSION = "0.1.1-SNAPSHOT"

  val log = Logger.getLogger(getClass.getName)

  def modules() = {
    Seq(
      new HttpModule(conf),
      new MetricsModule,
      new MarathonModule(conf),
      new MarathonRestModule,
      new EventModule(conf)
    ) ++ getEventsModule()
  }

  //TODO(*): Rethink how this could be done.
  def getEventsModule(): Option[AbstractModule] = {
    if (conf.eventSubscriber.isSupplied) {
      conf.eventSubscriber() match {
        case "http_callback" => {
          log.warning("Using HttpCallbackEventSubscriber for event" +
            "notification")
          return Some(new HttpEventModule())
        }
        case _ => {
          log.warning("Event notification disabled.")
        }
      }
    }

    None
  }

  //TOOD(FL): Make Events optional / wire up.
  lazy val conf = new ScallopConf(args)
    with HttpConf with MarathonConf with AppConfiguration
      with EventConfiguration with HttpEventConfiguration with ZookeeperConf

  run(List(
    classOf[HttpService],
    classOf[MarathonSchedulerService]
  ))
}