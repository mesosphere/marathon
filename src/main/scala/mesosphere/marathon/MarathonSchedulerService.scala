package mesosphere.marathon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ Timer, TimerTask }
import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.{ after, ask }
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
import mesosphere.util.PromiseActor
import mesosphere.util.state.FrameworkIdUtil
import org.apache.log4j.Logger
import org.apache.mesos.Protos.FrameworkID
import org.apache.mesos.SchedulerDriver

import scala.collection.immutable.Seq
import scala.concurrent.duration.{ MILLISECONDS, _ }
import scala.concurrent.{ Await, Future, TimeoutException }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

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
    driverFactory: SchedulerDriverFactory,
    system: ActorSystem,
    migration: Migration,
    @Named("schedulerActor") schedulerActor: ActorRef) extends AbstractExecutionThreadService with Leader {

  import mesosphere.util.ThreadPoolContext.context

  implicit val zkTimeout = config.zkTimeoutDuration

  val latch = new CountDownLatch(1)

  // Time to wait before trying to reconcile app tasks after driver starts
  val reconciliationInitialDelay =
    Duration(config.reconciliationInitialDelay(), MILLISECONDS)

  // Interval between task reconciliation operations
  val reconciliationInterval =
    Duration(config.reconciliationInterval(), MILLISECONDS)

  // Time to wait before trying to scale apps after driver starts
  val scaleAppsInitialDelay =
    Duration(config.scaleAppsInitialDelay(), MILLISECONDS)

  // Interval between attempts to scale apps
  val scaleAppsInterval =
    Duration(config.scaleAppsInterval(), MILLISECONDS)

  private[mesosphere] var timer = newTimer()

  val log = Logger.getLogger(getClass.getName)

  // FIXME: Remove from this class
  def frameworkId: Option[FrameworkID] = {
    val fid = frameworkIdUtil.fetch

    fid match {
      case Some(id) =>
        log.info(s"Setting framework ID to ${id.getValue}")
      case None =>
        log.info("No previous framework ID found")
    }

    fid
  }

  // This is a little ugly as we are using a mutable variable. But drivers can't
  // be reused (i.e. once stopped they can't be started again. Thus,
  // we have to allocate a new driver before each run or after each stop.
  var driver: Option[SchedulerDriver] = None

  implicit val timeout: Timeout = 5.seconds

  protected def newTimer() = new Timer("marathonSchedulerTimer")

  def deploy(plan: DeploymentPlan, force: Boolean = false): Future[Unit] = {
    log.info(s"Deploy plan:$plan with force:$force")
    val future: Future[Any] = PromiseActor.askWithoutTimeout(system, schedulerActor, Deploy(plan, force))
    future.map {
      case DeploymentStarted(_) => ()
      case CommandFailed(_, t)  => throw t
    }
  }

  def cancelDeployment(id: String): Unit =
    schedulerActor ! CancelDeployment(id)

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

    log.info("Cancelling timer")
    timer.cancel()

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
      driver.foreach(_.run())
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
    driver.foreach(_.stop(true)) // failover = true
    driver = None
  }

  def isLeader: Boolean = leader.get()

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
    driver = Some(driverFactory.createDriver())
    var migrationComplete = false
    try {
      //execute tasks, only the leader is allowed to
      migration.migrate()
      migrationComplete = true

      // We have been elected. Thus, elect leadership with the abdication command.
      electLeadership(Some(abdicateCmd))

      // We successfully took over leadership. Time to reset backoff
      resetOfferLeadershipBackOff()
    }
    catch {
      case NonFatal(e) => // catch Scala and Java exceptions
        log.error(s"Failed to take over leadership: $e")

        increaseOfferLeadershipBackOff()

        abdicateLeadership()

        if (!migrationComplete) {
          // here the driver is not running yet and therefore it cannot execute
          // the abdication command and offer the leadership. So we do it here
          abdicateCmd.execute()
          offerLeadership()
        }
    }
  }
  //End Leader interface

  private def defeatLeadership(): Unit = {
    log.info("Defeat leadership")

    schedulerActor ! Suspend(LostLeadershipException("Leadership was defeated"))

    timer.cancel()
    timer = newTimer()

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

    schedulerActor ! Start

    // Start the timer
    schedulePeriodicOperations()
  }

  def abdicateLeadership(): Unit = {
    log.info("Abdicating")

    // To abdicate we defeat our leadership
    defeatLeadership()
  }

  var offerLeadershipBackOff = 0.5.seconds
  val maximumOfferLeadershipBackOff = 16.seconds

  private def increaseOfferLeadershipBackOff() {
    if (offerLeadershipBackOff <= maximumOfferLeadershipBackOff) {
      offerLeadershipBackOff *= 2
      log.info(s"Increasing offerLeadership backoff to $offerLeadershipBackOff")
    }
  }

  private def resetOfferLeadershipBackOff() {
    log.info("Reset offerLeadership backoff")
    offerLeadershipBackOff = 0.5.seconds
  }

  private def offerLeadership(): Unit = {
    log.info(s"Will offer leadership after $offerLeadershipBackOff backoff")
    after(offerLeadershipBackOff, system.scheduler)(Future {
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
    })
  }

  private def schedulePeriodicOperations(): Unit = {

    timer.schedule(
      new TimerTask {
        def run() {
          if (isLeader) {
            schedulerActor ! ScaleApps
          }
          else log.info("Not leader therefore not scaling apps")
        }
      },
      scaleAppsInitialDelay.toMillis,
      scaleAppsInterval.toMillis
    )

    timer.schedule(
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
    timer.schedule(
      new TimerTask {
        def run() {
          if (isLeader) {
            taskTracker.expungeOrphanedTasks()
          }
        }
      },
      reconciliationInitialDelay.toMillis + reconciliationInterval.toMillis
    )
  }
}
