package mesosphere.marathon

import org.apache.mesos.Protos.{FrameworkInfo, FrameworkID}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import mesosphere.marathon.api.v1.ServiceDefinition
import mesosphere.marathon.state.MarathonStore
import com.google.common.util.concurrent.AbstractIdleService
import org.apache.mesos.state.State
import javax.inject.{Named, Inject}
import java.util.{TimerTask, Timer}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import scala.Option
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import java.lang.String
import scala.Predef.String

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    config: MarathonConfiguration,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    mesosState: State)
  extends AbstractIdleService with Leader {

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
    .setFailoverTimeout(Main.getConfiguration.mesosFailoverTimeout())
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

  //Begin Service interface
  def startUp() {
    log.info("Starting driver")
    if (! leader.get) {
      offerLeaderShip()
    }

    scheduleTaskBalancing()
    driver.run()
  }

  def shutDown() {
    log.info("Stopping driver")
    driver.stop()
  }

  def isLeader() = {
    leader.get() || getLeader.isEmpty
  }

  def getLeader: Option[String] = {
    if (candidate.nonEmpty && candidate.get.getLeaderData.isPresent) {
      return Some(new String(candidate.get.getLeaderData.get))
    }
    None
  }
  //End Service interface

  //Begin Leader interface, which is required for CandidateImpl.
  def onDefeated() {
    leader.set(false)
    shutDown()
    offerLeaderShip()
  }

  def onElected(cmd: ExceptionalCommand[JoinException]) {
    leader.set(true)
    startUp()
  }
  //End Leader interface

  private def scheduleTaskBalancing() {
    val timer = new Timer()
    val task = new TimerTask {
      def run() {
        scheduler.balanceTasks(driver)
      }
    }
    timer.schedule(task, balanceWait)
  }

  private def offerLeaderShip() {
    if (candidate.nonEmpty) {
      log.info("Offering leadership.")
      candidate.get.offerLeadership(this)
    }
  }
}
