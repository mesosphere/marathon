package mesosphere.mesos.simulation

import com.google.inject._
import com.google.inject.util.Modules
import mesosphere.marathon._

/**
  * Start marathon with a simulated mesos driver.
  */
class MarathonWithSimulatedMesos(args: Seq[String]) extends MarathonApp(args) {
  private[this] def simulatedDriverModule: Module = {
    new AbstractModule {
      override def configure(): Unit = {
        bind(classOf[SchedulerDriverFactory]).to(classOf[SimulatedSchedulerDriverFactory]).in(Scopes.SINGLETON)
      }
    }
  }

  override val modules: Seq[Module] = {
    Seq(Modules.`override`(super.modules: _*).`with`(simulatedDriverModule))
  }
}

object SimulateMesosMain {
  def main(args: Array[String]): Unit = {
    val main = new MarathonWithSimulatedMesos(args.toIndexedSeq)
    main.start()
  }
}
