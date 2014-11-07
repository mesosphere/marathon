package mesosphere.marathon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ Timer, TimerTask }
import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.util.concurrent.AbstractExecutionThreadService
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import com.twitter.common.zookeeper.Group.JoinException
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Migration, PathId, Timestamp }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentManager.{ CancelDeployment, DeploymentStepInfo }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.PromiseActor
import org.apache.log4j.Logger

import scala.collection.immutable.Seq
import scala.concurrent.duration.{ MILLISECONDS, _ }
import scala.concurrent.{ TimeoutException, Await, Future, Promise }
import scala.util.{ Failure, Random, Success }

/**
  * Wrapper class for the scheduler
  */
class MarathonSchedulerService @Inject() (
    healthCheckManager: HealthCheckManager,
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    config: MarathonConf,
    frameworkIdUtil: FrameworkIdUtil,
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    scheduler: MarathonScheduler,
    system: ActorSystem,
    migration: Migration,
    @Named("schedulerActor") schedulerActor: ActorRef) extends AbstractExecutionThreadService with Leader {

  import mesosphere.util.ThreadPoolContext.context

  implicit val zkTimeout = config.zkFutureTimeout

  val latch = new CountDownLatch(1)

  // Time to wait before trying to reconcile app tasks after driver starts
  val reconciliationInitialDelay =
    Duration(config.reconciliationInitialDelay(), MILLISECONDS)

  // Interval between task reconciliation operations
  val reconciliationInterval =
    Duration(config.reconciliationInterval(), MILLISECONDS)

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

  implicit val timeout: Timeout = 5.seconds

  def deploy(plan: DeploymentPlan, force: Boolean = false): Future[Unit] = {
    log.info(s"Deploy plan:$plan with force:$force")
    val promise = Promise[AnyRef]()
    val receiver = system.actorOf(Props(classOf[PromiseActor], promise))

    schedulerActor.tell(Deploy(plan, force), receiver)

    promise.future.map {
      case DeploymentStarted(_) => ()
      case CommandFailed(_, t)  => throw t
    }
  }

  def cancelDeployment(id: String): Unit =
    schedulerActor ! CancelDeployment(
      id,
      new DeploymentCanceledException("The upgrade has been cancelled")
    )

  def listApps(): Iterable[AppDefinition] =
    Await.result(appRepository.apps(), config.zkTimeoutDuration)

  def listAppVersions(appId: PathId): Iterable[Timestamp] =
    Await.result(appRepository.listVersions(appId), config.zkTimeoutDuration)

  def listRunningDeployments(): Future[Seq[(DeploymentPlan, DeploymentStepInfo)]] =
    (schedulerActor ? RetrieveRunningDeployments)
      .recoverWith {
        case _: TimeoutException =>
          Future.failed(new TimeoutException(s"Can not retrieve the list of running deployments in time"))
      }
      .mapTo[RunningDeployments]
      .map(_.plans)

  def getApp(appId: PathId): Option[AppDefinition] = {
    Await.result(appRepository.currentVersion(appId), config.zkTimeoutDuration)
  }

  def getApp(appId: PathId, version: Timestamp): Option[AppDefinition] = {
    Await.result(appRepository.app(appId, version), config.zkTimeoutDuration)
  }

  def killTasks(
    appId: PathId,
    tasks: Iterable[MarathonTask],
    scale: Boolean): Iterable[MarathonTask] = {
    schedulerActor ! KillTasks(appId, tasks.map(_.getId).toSet, scale)

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
    schedulePeriodicOperations()

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

  def isLeader(): Boolean = leader.get()

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

    //execute tasks, only the leader is allowed to
    migration.migrate()

    // We have been elected. Thus, elect leadership with the abdication command.
    electLeadership(Some(abdicateCmd))
  }
  //End Leader interface

  private def defeatLeadership(): Unit = {
    log.info("Defeat leadership")

    // Our leadership has been defeated. Thus, update leadership and stop the driver.
    // Note that abdication command will be ran upon driver shutdown.
    leader.set(false)

    // Stop all health checks
    healthCheckManager.removeAll()

    stopDriver()
  }

  private def electLeadership(abdicateOption: Option[ExceptionalCommand[JoinException]]): Unit = {
    log.info("Elect leadership")

    // We have been elected as leader. Thus, update leadership and run the driver.
    leader.set(true)
    runDriver(abdicateOption)

    // Create health checks for any existing apps
    listApps foreach healthCheckManager.reconcileWith
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

  private def schedulePeriodicOperations(): Unit = {
    reconciliationTimer.schedule(
      new TimerTask {
        def run() {
          if (isLeader) {
            schedulerActor ! ReconcileTasks
            schedulerActor ! ReconcileHealthChecks
          }
          else log.info("Not leader therefore not reconciling tasks")
        }
      },
      reconciliationInitialDelay.toMillis,
      reconciliationInterval.toMillis
    )

    // Tasks are only expunged once after the application launches
    // Wait until reconciliation is definitely finished so that we are guaranteed
    // to have loaded in all apps
    reconciliationTimer.schedule(
      new TimerTask {
        def run() {
          taskTracker.expungeOrphanedTasks()
        }
      },
      reconciliationInitialDelay.toMillis + reconciliationInterval.toMillis
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
