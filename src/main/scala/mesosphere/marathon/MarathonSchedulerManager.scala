package mesosphere.marathon

import org.apache.mesos.Protos.{FrameworkInfo, FrameworkID}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import scala.collection.mutable
import com.yammer.dropwizard.lifecycle.Managed
import mesosphere.marathon.api.v1.ServiceDefinition
import mesosphere.marathon.state.MarathonStore

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerManager(config: MarathonConfiguration) extends Managed {

  val log = Logger.getLogger(getClass.getName)

  val services = new mutable.HashMap[String, ServiceDefinition]

  val frameworkName = "marathon-" + config.version

  val frameworkId = FrameworkID.newBuilder.setValue(frameworkName).build

  val frameworkInfo = FrameworkInfo.newBuilder()
    .setName(frameworkName)
    .setId(frameworkId)
    .setUser("") // Let Mesos assign the user
    .build()

  log.info("Starting scheduler " + frameworkName)

  val store = MarathonStore("localhost:2181")
  val scheduler = new MarathonScheduler(store)
  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo, config.mesosMaster)

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

  def start() {
    log.info("Starting driver")
    new Thread() {
      override def run {
        driver.run()
      }
    }.start()
  }

  def stop() {
    log.info("Stopping driver")
    driver.stop()
  }

}
