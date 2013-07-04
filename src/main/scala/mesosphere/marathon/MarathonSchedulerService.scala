package mesosphere.marathon

import org.apache.mesos.Protos.{FrameworkInfo, FrameworkID}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import scala.collection.mutable
import mesosphere.marathon.api.v1.ServiceDefinition
import mesosphere.marathon.state.MarathonStore
import com.google.common.util.concurrent.AbstractIdleService
import org.apache.mesos.state.State
import javax.inject.Inject

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(config: MarathonConfiguration,
                                         mesosState: State) extends AbstractIdleService {

  val log = Logger.getLogger(getClass.getName)

  val services = new mutable.HashMap[String, ServiceDefinition]

  val frameworkName = "marathon-" + Main.VERSION

  val frameworkId = FrameworkID.newBuilder.setValue(frameworkName).build

  val frameworkInfo = FrameworkInfo.newBuilder()
    .setName(frameworkName)
    .setId(frameworkId)
    .setUser("") // Let Mesos assign the user
    .build()

  log.info("Starting scheduler " + frameworkName)

  val store = new MarathonStore(mesosState, () => new ServiceDefinition)
  val scheduler = new MarathonScheduler(store)
  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo, config.mesosMaster.get.get)

  // TODO: on startup, make sure correct number of processes are running

  def startService(service: ServiceDefinition) {
    scheduler.startService(driver, service)
  }

  def stopService(service: ServiceDefinition) {
    scheduler.stopService(driver, service)
  }

  def scaleService(service: ServiceDefinition) {
    scheduler.scaleService(driver, service)
  }

  def startUp() {
    log.info("Starting driver")
    driver.run()
  }

  def shutDown() {
    log.info("Stopping driver")
    driver.stop()
  }

}
