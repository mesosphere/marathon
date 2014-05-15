package mesosphere.marathon

import org.apache.mesos.Protos.{TaskID, FrameworkInfo}
import org.apache.mesos.MesosSchedulerDriver
import org.apache.log4j.Logger
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.{AppRepository, Timestamp}
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
import mesosphere.marathon.health.HealthCheckManager
import scala.concurrent.duration._
import java.util.concurrent.CountDownLatch

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerService @Inject()(
    healthCheckManager: HealthCheckManager,
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    config: MarathonConf,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    appRepository: AppRepository,
    frameworkIdUtil: FrameworkIdUtil,
    scheduler: MarathonScheduler)
  extends AbstractExecutionThreadService with Leader {

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  val latch = new CountDownLatch(1)

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
    .setUser(config.mesosUser())
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

  // This is a little ugly as we are using a mutable variable. But drivers can't be reused (i.e. once stopped they can't
  // be started again. Thus, we have to allocate a new driver before each run or after each stop.
  var driver = newDriver()

  def defaultWait = {
    appRepository.defaultWait
  }

  def startApp(app: AppDefinition): Future[_] = {
    // Backwards compatibility
    val oldPorts = app.ports
    val newPorts = oldPorts.map(p => if (p == 0) newAppPort(app) else p)

    if (oldPorts != newPorts) {
      val asMsg = Seq(oldPorts, newPorts).map("[" + _.mkString(", ") + "]")
      log.info(s"Assigned some ports for ${app.id}: ${asMsg.mkString(" -> ")}")
    }

    scheduler.startApp(driver, app.copy(ports = newPorts))
  }

  def stopApp(app: AppDefinition): Future[_] = {
    scheduler.stopApp(driver, app)
  }

  def updateApp(appName: String, appUpdate: AppUpdate): Future[_] =
    scheduler.updateApp(driver, appName, appUpdate).map { updatedApp =>
      scheduler.scale(driver, updatedApp)
    }

  def listApps(): Iterable[AppDefinition] =
    Await.result(appRepository.apps, defaultWait)

  def listAppVersions(appName: String): Iterable[Timestamp] =
    Await.result(appRepository.listVersions(appName), defaultWait)

  def getApp(appName: String): Option[AppDefinition] = {
    Await.result(appRepository.currentVersion(appName), defaultWait)
  }

  def getApp(appName: String, version: Timestamp) : Option[AppDefinition] = {
    Await.result(appRepository.app(appName, version), defaultWait)
  }

  def killTasks(
    appName: String,
    tasks: Iterable[MarathonTask],
    scale: Boolean
  ): Iterable[MarathonTask] = {
    if (scale) {
      getApp(appName) foreach { app =>
        val appUpdate = AppUpdate(instances = Some(app.instances - tasks.size))
        Await.result(scheduler.updateApp(driver, appName, appUpdate), defaultWait)
      }
    }

    tasks.foreach { task =>
      log.info(f"Killing task ${task.getId} on host ${task.getHost}")
      driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
    }

    tasks
  }

  //Begin Service interface

  override def startUp(): Unit = {
    log.info("Starting up")
    super.startUp()
  }

  override def run(): Unit = {
    log.info("Beginning run")

    // The first thing we do is offer our leadership. If using Zookeeper for leadership election than we will wait to be
    // elected. If we aren't (i.e. no HA) then we take over leadership run the driver immediately.
    offerLeadership()

    // Start the timer that handles reconciliation
    scheduleTaskReconciliation()

    // Block on the latch which will be countdown only when shutdown has been triggered. This is to prevent run()
    // from exiting.
    latch.await()

    log.info("Completed run")
  }

  override def triggerShutdown(): Unit = {
    log.info("Shutdown triggered")

    leader.set(false)

    stopDriver()

    log.info("Cancelling reconciliation timer")
    reconciliationTimer.cancel

    log.info("Removing the blocking of run()")

    // The countdown latch blocks run() from exiting. Counting down the latch removes the block.
    latch.countDown()

    super.triggerShutdown()
  }

  def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = {
    log.info("Running driver")
    listApps foreach healthCheckManager.reconcileWith

    // The following block asynchronously runs the driver. Note that driver.run() blocks until the driver has been
    // stopped (or aborted).
    Future {
      driver.run()
    }.map {
      _ => log.info("Driver stopped normally")
    }.recover {
      case e => log.error("Exception while running driver: " + e.getMessage)
    }.onComplete {
      _ => {
        log.info("Driver future completed. Executing optional abdication command.")

        // If there is an abdication command we need to execute it so that our leadership is given up. Note that executing
        // the abdication command does a few things:
        // - It causes onDefeated() to be executed (which is part of the Leader interface).
        // - It removes us as a leadership candidate. We must offer out leadership candidacy if we ever want to become
        //   the leader again in the future.
        //
        // If we don't have a abdication command we simply mark ourselves as not the leader
        abdicateCmdOption.fold(leader.set(false))(_.execute)

        // If we are shutting down than don't offer leadership. But if we aren't than the driver was stopped via external
        // means. For example, our leadership could have been defeated or perhaps it was abdicated. Therfore, for these
        // cases we offer our leadership again.
        if (isRunning) {
          offerLeadership()
        }
      }
    }
  }

  def stopDriver(): Unit = {
    log.info("Stopping driver")

    // Stopping the driver will cause the driver run() method to return.
    driver.stop(true) // failover = true

    // We need to allocate a new driver as drivers can't be reused. Once they are in the stopped state they cannot be
    // restarted. See the Mesos C++ source code for the MesosScheduleDriver.
    driver = newDriver()
  }

  def isLeader = {
    leader.get()
  }

  def getLeader: Option[String] = {
    candidate.flatMap {
      c =>
        if (c.getLeaderData.isPresent)
          Some(new String(c.getLeaderData.get))
        else
          None
    }
  }
  //End Service interface

  //Begin Leader interface, which is required for CandidateImpl.
  override def onDefeated(): Unit = {
    log.info("Defeated (Leader Interface)")

    // Our leadership has been defeated and thus we call the defeatLeadership() method.
    defeatLeadership()
  }

  override def onElected(abdicateCmd: ExceptionalCommand[JoinException]): Unit = {
    log.info("Elected (Leader Interface)")

    // We have been elected. Thus, elect leadership with the abdication command.
    electLeadership(Some(abdicateCmd))
  }
  //End Leader interface

  private def defeatLeadership() {
    log.info("Defeat leadership")

    // Our leadership has been defeated. Thus, update leadership and stop the driver.
    // Note that abdication command will be ran upon driver shutdown.
    leader.set(false)

    stopDriver()
  }

  private def electLeadership(abdicateOption: Option[ExceptionalCommand[JoinException]]) {
    log.info("Elect leadership")

    // We have been elected as leader. Thus, update leadership and run the driver.
    leader.set(true)
    runDriver(abdicateOption)
  }

  def abdicateLeadership() = {
    log.info("Abdicating")

    // To abdicate we defeat our leadership
    defeatLeadership()
  }

  private def offerLeadership(): Unit = {
    log.info("Offering leadership")

    candidate.synchronized {
      candidate.fold {
        // In this case we aren't using Zookeeper for leadership election. Thus, we simply elect ourselves as leader.
        log.info("Not using HA and therefore electing as leader by default")
        electLeadership(None)
      } {
        c => {
          // In this case we care using Zookeeper for leadership candidacy. Thus, offer our leadership.
          log.info("Using HA and therefore offering leadership")
          c.offerLeadership(this)
        }
      }
    }
  }

  private def scheduleTaskReconciliation() {
    reconciliationTimer.schedule(
      new TimerTask {
        def run() {
          if (isLeader) {
            scheduler.reconcileTasks(driver)
          } else log.info("Not leader therefore not reconciling tasks")
        }
      },
      reconciliationInitialDelay.toMillis,
      reconciliationFrequency.toMillis
    )
  }

  private def newAppPort(app: AppDefinition): Integer = {
    // TODO this is pretty expensive, find a better way
    val assignedPorts = listApps().map(_.ports).flatten.toSeq
    var port = 0
    do {
      port = config.localPortMin() + Random.nextInt(config.localPortMax() - config.localPortMin())
    } while (assignedPorts.contains(port))
    port
  }

  private def newDriver(): MesosSchedulerDriver = {
    new MesosSchedulerDriver(
      scheduler,
      frameworkInfo.build,
      config.mesosMaster()
    )
  }
}
