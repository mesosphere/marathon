package mesosphere.marathon

import org.apache.mesos.Protos.{TaskID, FrameworkInfo}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.MarathonStore
import com.google.common.util.concurrent.AbstractExecutionThreadService
import javax.inject.{Named, Inject}
import java.util.{TimerTask, Timer}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.{Duration, MILLISECONDS}
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import scala.Option
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import scala.util.Random
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.marathon.Protos.MarathonTask

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    config: MarathonConf,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    store: MarathonStore[AppDefinition],
    frameworkIdUtil: FrameworkIdUtil,
    scheduler: MarathonScheduler)
  extends AbstractExecutionThreadService with Leader {

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  // Time to wait before trying to reconcile app tasks after driver starts
  val reconciliationInitialDelay =
    Duration(config.reconciliationInitialDelay(), MILLISECONDS)

  // Interval between task reconciliation operations
  val reconciliationFrequency =
    Duration(config.reconciliationFrequency(), MILLISECONDS)

  val reconciliationTimer = new Timer("reconciliationTimer")

  val log = Logger.getLogger(getClass.getName)

  val frameworkName = "marathon-" + Main.properties.getProperty("marathon.version")

  val frameworkInfo = FrameworkInfo.newBuilder()
    .setName(frameworkName)
    .setFailoverTimeout(config.mesosFailoverTimeout())
    .setUser("") // Let Mesos assign the user
    .setCheckpoint(config.checkpoint())


  // Set the framework ID
  frameworkIdUtil.fetch() match {
    case Some(id) => {
      log.info(s"Setting framework ID to ${id.getValue}")
      frameworkInfo.setId(id)
    }
    case None => {
      log.info("No previous framework ID found")
    }
  }
  // Set the role, if provided.
  config.mesosRole.get.map(frameworkInfo.setRole)

  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo.build, config.mesosMaster())

  var abdicateCmd: Option[ExceptionalCommand[JoinException]] = None

  def defaultWait = {
    store.defaultWait
  }

  def startApp(app: AppDefinition): Future[_] = {
    // Backwards compatibility
    val oldPorts = app.ports
    val newPorts = if (oldPorts == Nil) {
      Seq(newAppPort(app))
    } else {
      oldPorts.map(port => if (port == 0) newAppPort(app) else port)
    }

    if (oldPorts != newPorts) {
      val asMsg = Seq(oldPorts, newPorts).map("[" + _.mkString(", ") + "]")
      log.info(s"Assigned some ports for ${app.id}: ${asMsg.mkString(" -> ")}")
      app.ports = newPorts
    }

    scheduler.startApp(driver, app)
  }

  def stopApp(app: AppDefinition): Future[_] = {
    scheduler.stopApp(driver, app)
  }

  def updateApp(id: String, appUpdate: AppUpdate): Future[_] = {
    scheduler.updateApp(driver, id, appUpdate)
  }

  @deprecated("The scale operation has been subsumed by update in the v2 API.")
  def scaleApp(app: AppDefinition, applyNow: Boolean = true): Future[_] = {
    scheduler.scaleApp(driver, app, applyNow)
  }

  def listApps(): Seq[AppDefinition] = {
    // TODO method is expensive, it's n+1 trips to ZK. Cache this?
    val names = Await.result(store.names(), defaultWait)
    val futures = names.map(name => store.fetch(name))
    val futureServices = Future.sequence(futures)
    Await.result(futureServices, defaultWait).map(_.get).toSeq
  }

  def getApp(appName: String): Option[AppDefinition] = {
    val future = store.fetch(appName)
    Await.result(future, defaultWait)
  }

  def killTasks(appName: String, tasks: Iterable[MarathonTask], scale: Boolean): Iterable[MarathonTask] = {
    if (scale) {
      getApp(appName) match {
        case Some(appDef) =>
          appDef.instances = appDef.instances - tasks.size

          Await.result(scaleApp(appDef, false), defaultWait)
        case None =>
      }
    }

    tasks.map(task => {
      log.info(f"Killing task ${task.getId} on host ${task.getHost}")
      driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
      task
    })
  }

  //Begin Service interface
  def run() {
    log.info("Starting up")
    if (leader.get) {
      runDriver()
    } else {
      offerLeadership()
    }
  }

  override def triggerShutdown() {
    log.info("Shutting down")
    abdicateCmd.map(_.execute)
    stopDriver()
    reconciliationTimer.cancel
  }

  def runDriver() {
    log.info("Running driver")
    scheduleTaskReconciliation
    driver.run()
  }

  def stopDriver() {
    log.info("Stopping driver")
    driver.stop(true) // failover = true
  }

  def isLeader = {
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
    log.info("Defeated")
    leader.set(false)
    stopDriver()

    // Don't offer leadership if we're shutting down
    if (isRunning) {
      offerLeadership()
    }
  }

  def onElected(abdicate: ExceptionalCommand[JoinException]) {
    log.info("Elected")
    abdicateCmd = Some(abdicate)
    leader.set(true)
    runDriver()
  }
  //End Leader interface

  private def scheduleTaskReconciliation {
    reconciliationTimer.schedule(
      new TimerTask { def run() { scheduler.reconcileTasks(driver) }},
      reconciliationInitialDelay.toMillis,
      reconciliationFrequency.toMillis
    )
  }

  private def offerLeadership() {
    if (candidate.nonEmpty) {
      log.info("Offering leadership.")
      candidate.get.offerLeadership(this)
    }
  }

  private def newAppPort(app: AppDefinition): Int = {
    // TODO this is pretty expensive, find a better way
    val assignedPorts = listApps().map(_.ports).flatten
    var port = 0
    do {
      port = config.localPortMin() + Random.nextInt(config.localPortMax() - config.localPortMin())
    } while (assignedPorts.contains(port))
    port
  }
}
