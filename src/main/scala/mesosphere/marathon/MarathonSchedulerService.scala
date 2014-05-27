package mesosphere.marathon

import org.apache.mesos.Protos.TaskID
import org.apache.log4j.Logger
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.{ AppRepository, Timestamp }
import com.google.common.util.concurrent.AbstractExecutionThreadService
import javax.inject.{ Named, Inject }
import java.util.{ TimerTask, Timer }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import scala.util.Random
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.HealthCheckManager
import scala.concurrent.duration._
import java.util.concurrent.CountDownLatch
import mesosphere.util.{ BackToTheFuture, ThreadPoolContext }
import akka.actor.ActorRef
import mesosphere.marathon.MarathonSchedulerActor._
import akka.pattern.ask
import scala.util.{Failure, Success}
import akka.util.Timeout

/**
  * Wrapper class for the scheduler
  *
  * @author Tobi Knaup
  */
class MarathonSchedulerService @Inject() (
  healthCheckManager: HealthCheckManager,
  @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
  config: MarathonConf,
  frameworkIdUtil: FrameworkIdUtil,
  @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
  appRepository: AppRepository,
  scheduler: MarathonScheduler,
  @Named("schedulerActor") schedulerActor: ActorRef
) extends AbstractExecutionThreadService with Leader {

  import ThreadPoolContext.context

  implicit val zkTimeout = config.zkFutureTimeout

  val latch = new CountDownLatch(1)

  // Time to wait before trying to reconcile app tasks after driver starts
  val reconciliationInitialDelay =
    Duration(config.reconciliationInitialDelay(), MILLISECONDS)

  // Interval between task reconciliation operations
  val reconciliationFrequency =
    Duration(config.reconciliationFrequency(), MILLISECONDS)

  val reconciliationTimer = new Timer("reconciliationTimer")

  val log = Logger.getLogger(getClass.getName)

  val frameworkId = frameworkIdUtil.fetch
  frameworkId match {
    case Some(id) =>
      log.info(s"Setting framework ID to ${id.getValue}")
    case None =>
      log.info("No previous framework ID found")
  }

  // This is a little ugly as we are using a mutable variable. But drivers can't
  // be reused (i.e. once stopped they can't be started again. Thus,
  // we have to allocate a new driver before each run or after each stop.
  var driver = MarathonSchedulerDriver.newDriver(config, scheduler, frameworkId)


  implicit val timeout: Timeout = defaultWait

  def startApp(app: AppDefinition): Future[_] = {
    // Backwards compatibility
    val oldPorts = app.ports
    val newPorts = oldPorts.map(p => if (p == 0) newAppPort(app) else p)

    if (oldPorts != newPorts) {
      val asMsg = Seq(oldPorts, newPorts).map("[" + _.mkString(", ") + "]")
      log.info(s"Assigned some ports for ${app.id}: ${asMsg.mkString(" -> ")}")
    }

    schedulerActor ? StartApp(app.copy(ports = newPorts))
  }

  def stopApp(app: AppDefinition): Future[_] = {
    schedulerActor ? StopApp(app)
  }

  def updateApp(appName: String, appUpdate: AppUpdate): Future[_] =
    (schedulerActor ? UpdateApp(appName, appUpdate)) flatMap { _ =>
      schedulerActor ? ScaleApp(appName)
    }

  def upgradeApp(app: AppDefinition, keepAlive: Int, force:Boolean=false): Future[Boolean] = {
    // TODO: this should be configurable
    implicit val timeout = Timeout(12.hours)
    val message = if (force) RollbackApp(app, keepAlive) else UpgradeApp(app, keepAlive)
    (schedulerActor ? message).map {
      case CommandFailed(_, reason) => throw reason
      case _ => true
    }
  }

  def listApps(): Iterable[AppDefinition] =
    Await.result(appRepository.apps, config.zkTimeoutDuration)

  def listAppVersions(appName: String): Iterable[Timestamp] =
    Await.result(appRepository.listVersions(appName), config.zkTimeoutDuration)

  def getApp(appName: String): Option[AppDefinition] = {
    Await.result(appRepository.currentVersion(appName), config.zkTimeoutDuration)
  }

  def getApp(appName: String, version: Timestamp): Option[AppDefinition] = {
    Await.result(appRepository.app(appName, version), config.zkTimeoutDuration)
  }

  def killTasks(
    appName: String,
    tasks: Iterable[MarathonTask],
    scale: Boolean): Iterable[MarathonTask] = {
    if (scale) {
      getApp(appName) foreach { app =>
        val appUpdate = AppUpdate(instances = Some(app.instances - tasks.size))
        Await.result(schedulerActor ? UpdateApp(appName, appUpdate), defaultWait)
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

    // The first thing we do is offer our leadership. If using Zookeeper for
    // leadership election then we will wait to be elected. If we aren't (i.e.
    // no HA) then we take over leadership run the driver immediately.
    offerLeadership()

    // Start the timer that handles reconciliation
    scheduleTaskReconciliation()

    // Block on the latch which will be countdown only when shutdown has been
    // triggered. This is to prevent run()
    // from exiting.
    latch.await()

    log.info("Completed run")
  }

  override def triggerShutdown(): Unit = {
    log.info("Shutdown triggered")

    leader.set(false)

    stopDriver()

    log.info("Cancelling reconciliation timer")
    reconciliationTimer.cancel()

    log.info("Removing the blocking of run()")

    // The countdown latch blocks run() from exiting. Counting down the latch removes the block.
    latch.countDown()

    super.triggerShutdown()
  }

  def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = {
    log.info("Running driver")
    listApps foreach healthCheckManager.reconcileWith

    // The following block asynchronously runs the driver. Note that driver.run()
    // blocks until the driver has been stopped (or aborted).
    Future {
      driver.run()
    } onComplete {
      case Success(_) =>
        log.info("Driver future completed. Executing optional abdication command.")

        // If there is an abdication command we need to execute it so that our
        // leadership is given up. Note that executing the abdication command
        // does a few things: - It causes onDefeated() to be executed (which is
        // part of the Leader interface).  - It removes us as a leadership
        // candidate. We must offer out leadership candidacy if we ever want to
        // become the leader again in the future.
        //
        // If we don't have a abdication command we simply mark ourselves as
        // not the leader
        abdicateCmdOption match {
          case Some(cmd) => cmd.execute()
          case _         => leader.set(false)
        }

        // If we are shutting down then don't offer leadership. But if we
        // aren't then the driver was stopped via external means. For example,
        // our leadership could have been defeated or perhaps it was
        // abdicated. Therefore, for these cases we offer our leadership again.
        if (isRunning) {
          offerLeadership()
        }
      case Failure(t) =>
        log.error("Exception while running driver", t)
    }
  }

  def stopDriver(): Unit = {
    log.info("Stopping driver")

    // Stopping the driver will cause the driver run() method to return.
    driver.stop(true) // failover = true

    // We need to allocate a new driver as drivers can't be reused. Once they
    // are in the stopped state they cannot be restarted. See the Mesos C++
    // source code for the MesosScheduleDriver.
    driver = MarathonSchedulerDriver.newDriver(config, scheduler, frameworkId)
  }

  def isLeader = {
    leader.get()
  }

  def getLeader: Option[String] = {
    candidate.flatMap { c =>
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

  private def defeatLeadership(): Unit = {
    log.info("Defeat leadership")

    // Our leadership has been defeated. Thus, update leadership and stop the driver.
    // Note that abdication command will be ran upon driver shutdown.
    leader.set(false)

    stopDriver()
  }

  private def electLeadership(abdicateOption: Option[ExceptionalCommand[JoinException]]): Unit = {
    log.info("Elect leadership")

    // We have been elected as leader. Thus, update leadership and run the driver.
    leader.set(true)
    runDriver(abdicateOption)
  }

  def abdicateLeadership(): Unit = {
    log.info("Abdicating")

    // To abdicate we defeat our leadership
    defeatLeadership()
  }

  private def offerLeadership(): Unit = {
    log.info("Offering leadership")

    candidate.synchronized {
      candidate match {
        case Some(c) =>
          // In this case we care using Zookeeper for leadership candidacy.
          // Thus, offer our leadership.
          log.info("Using HA and therefore offering leadership")
          c.offerLeadership(this)
        case _ =>
          // In this case we aren't using Zookeeper for leadership election.
          // Thus, we simply elect ourselves as leader.
          log.info("Not using HA and therefore electing as leader by default")
          electLeadership(None)
      }
    }
  }

  private def scheduleTaskReconciliation(): Unit = {
    reconciliationTimer.schedule(
      new TimerTask {
        def run() {
          if (isLeader) {
            schedulerActor ! ReconcileTasks
          }
          else log.info("Not leader therefore not reconciling tasks")
        }
      },
      reconciliationInitialDelay.toMillis,
      reconciliationFrequency.toMillis
    )
  }

  private def newAppPort(app: AppDefinition): Integer = {
    // TODO this is pretty expensive, find a better way
    val assignedPorts = listApps().flatMap(_.ports).toSet
    val portSum = config.localPortMax() - config.localPortMin()

    // prevent infinite loop if all ports are taken
    if (assignedPorts.size >= portSum)
      throw new PortRangeExhaustedException(config.localPortMin(), config.localPortMax())

    var port = 0
    do {
      port = config.localPortMin() + Random.nextInt(portSum)
    } while (assignedPorts.contains(port))
    port
  }
}
