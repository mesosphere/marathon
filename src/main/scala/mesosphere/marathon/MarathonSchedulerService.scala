package mesosphere.marathon

import org.apache.mesos.Protos.{FrameworkInfo, FrameworkID}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import mesosphere.marathon.api.v1.ServiceDefinition
import mesosphere.marathon.state.MarathonStore
import com.google.common.util.concurrent.AbstractIdleService
import org.apache.mesos.state.State
import javax.inject.Inject
import java.util.{TimerTask, Timer}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(config: MarathonConfiguration,
                                         mesosState: State) extends AbstractIdleService {

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  // Time to wait before trying to balance app tasks after driver starts
  val balanceWait = Duration(10, "seconds").toMillis

  val log = Logger.getLogger(getClass.getName)

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

  def startService(service: ServiceDefinition) {
    scheduler.startService(driver, service)
  }

  def stopService(service: ServiceDefinition) {
    scheduler.stopService(driver, service)
  }

  def scaleService(service: ServiceDefinition) {
    scheduler.scaleService(driver, service)
  }

  def listServices(): Seq[ServiceDefinition] = {
    val names = Await.result(store.names(), store.defaultWait)
    val futures = names.map(name => store.fetch(name))
    val futureServices = Future.sequence(futures)
    Await.result(futureServices, store.defaultWait).map(_.get).toSeq
  }

  def startUp() {
    log.info("Starting driver")
    scheduleTaskBalancing()
    driver.run()
  }

  def shutDown() {
    log.info("Stopping driver")
    driver.stop()
  }

  private

  def scheduleTaskBalancing() {
    val timer = new Timer()
    val task = new TimerTask {
      def run() {
        scheduler.balanceTasks(driver)
      }
    }
    timer.schedule(task, balanceWait)
  }

}
