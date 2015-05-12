package mesosphere.mesos.simulation

import akka.actor.Props
import org.apache.mesos.{ Scheduler, SchedulerDriver }

private class SimulatedDriverWiring(scheduler: Scheduler) {
  private lazy val schedulerActorProps = Props(new SchedulerActor(scheduler))
  private lazy val driverActorProps = Props(new DriverActor(schedulerActorProps))
  lazy val driver = new SimulatedDriver(driverActorProps)
}

/**
  * A factory for a simulated [[SchedulerDriver]].
  */
object SimulatedDriverWiring {
  def createDriver(scheduler: Scheduler): SchedulerDriver = {
    new SimulatedDriverWiring(scheduler).driver
  }
}
