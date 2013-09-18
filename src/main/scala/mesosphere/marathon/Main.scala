package mesosphere.marathon

import mesosphere.Application
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
object Main extends Application {

  val VERSION = "0.0.1"

  val log = Logger.getLogger(getClass.getName)

  def getModules() = {
    Seq(
      new HttpModule(getConfiguration),
      new MetricsModule,
      new MarathonModule(getConfiguration),
      new MarathonRestModule,
      new EventModule(getConfiguration)
    ) ++ getEventsModule()
  }

  //TODO(*): Rethink how this could be done.
  def getEventsModule(): Option[AbstractModule] = {
    if (getConfiguration.eventSubscriber.isSupplied) {
      getConfiguration.eventSubscriber() match {
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
  lazy val getConfiguration = new ScallopConf(args)
    with HttpConf with MarathonConfiguration with AppConfiguration
      with EventConfiguration with HttpEventConfiguration

  run(List(
    classOf[HttpService],
    classOf[MarathonSchedulerService]
  ))
}