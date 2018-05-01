package mesosphere.chaos.example

import org.rogach.scallop.ScallopConf
import mesosphere.chaos.http.{ HttpService, HttpConf, HttpModule }
import mesosphere.chaos.metrics.MetricsModule
import mesosphere.chaos.{ App, AppConfiguration }

object Main extends App {

  //Declare all Guice Modules
  def modules() = {
    Seq(
      new HttpModule(conf),
      new MetricsModule,
      new ExampleRestModule)
  }

  //The fact that this is lazy, allows us to pass it to a Module
  //constructor.
  lazy val conf = new ScallopConf(args) with HttpConf with AppConfiguration

  run(classOf[HttpService])
}
