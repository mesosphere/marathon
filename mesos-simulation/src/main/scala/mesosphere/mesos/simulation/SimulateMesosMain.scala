package mesosphere.mesos.simulation

import com.google.inject._
import com.google.inject.util.Modules
import mesosphere.marathon._

/**
  * Start marathon with a simulated mesos driver.
  */
object SimulateMesosMain extends MarathonApp {
  private[this] def simulatedDriverModule: Module = {
    new AbstractModule {
      override def configure(): Unit = {
        bind(classOf[SchedulerDriverFactory]).to(classOf[SimulatedSchedulerDriverFactory]).in(Scopes.SINGLETON)
      }
    }
  }

  override def modules(): Seq[Module] = {
    Seq(Modules.`override`(super.modules(): _*).`with`(simulatedDriverModule))
  }

  runDefault()
}
