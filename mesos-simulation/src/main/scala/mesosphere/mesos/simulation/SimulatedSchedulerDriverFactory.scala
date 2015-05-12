package mesosphere.mesos.simulation

import javax.inject.Inject

import mesosphere.marathon.{ MarathonScheduler, MarathonSchedulerDriverHolder, SchedulerDriverFactory }
import org.apache.mesos.SchedulerDriver

class SimulatedSchedulerDriverFactory @Inject() (
  holder: MarathonSchedulerDriverHolder,
  newScheduler: MarathonScheduler)
    extends SchedulerDriverFactory {

  override def createDriver(): SchedulerDriver = {
    val driver = SimulatedDriverWiring.createDriver(newScheduler)
    holder.driver = Some(driver)
    driver
  }
}
