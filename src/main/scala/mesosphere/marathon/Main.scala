package mesosphere.marathon

import mesosphere.Application
import org.rogach.scallop.ScallopConf
import mesosphere.chaos.http.{HttpService, HttpModule, HttpConf}
import mesosphere.chaos.metrics.MetricsModule

/**
 * @author Tobi Knaup
 */
object Main extends Application {

  val VERSION = "0.0.1"

  def getModules() = {
    Seq(
      new HttpModule(getConfiguration),
      new MetricsModule,
      new MarathonModule(getConfiguration)
    )
  }

  lazy val getConfiguration = new ScallopConf(args) with HttpConf with MarathonConfiguration

  run(List(
    classOf[HttpService],
    classOf[MarathonSchedulerService]
  ))
}