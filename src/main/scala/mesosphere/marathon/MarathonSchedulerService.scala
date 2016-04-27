package mesosphere.marathon

import java.util.concurrent.CountDownLatch
import java.util.{ Timer, TimerTask }
import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import akka.pattern.{ after, ask }
import akka.util.Timeout
import com.google.common.util.concurrent.AbstractExecutionThreadService
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService }
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.event.EventModule
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Migration, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentManager.{ CancelDeployment, DeploymentStepInfo }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.PromiseActor
import mesosphere.util.state.FrameworkIdUtil
import org.apache.mesos.Protos.FrameworkID
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory
import com.codahale.metrics.MetricRegistry

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, TimeoutException }
import scala.util.{ Failure, Success }

/**
  * Wrapper class for the scheduler
  */
class MarathonSchedulerService @Inject() (
  leadershipCoordinator: LeadershipCoordinator,
  healthCheckManager: HealthCheckManager,
  config: MarathonConf,
  frameworkIdUtil: FrameworkIdUtil,
  electionService: ElectionService,
  appRepository: AppRepository,
  driverFactory: SchedulerDriverFactory,
  system: ActorSystem,
  migration: Migration,
  @Named("schedulerActor") schedulerActor: ActorRef,
  @Named(EventModule.busName) eventStream: EventStream,
  metrics: Metrics = new Metrics(new MetricRegistry))
    extends AbstractExecutionThreadService with ElectionCandidate {

  import scala.concurrent.ExecutionContext.Implicits.global

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

  val log = LoggerFactory.getLogger(getClass.getName)

  // FIXME: Remove from this class
  def frameworkId: Option[FrameworkID] = frameworkIdUtil.fetch()

  // This is a little ugly as we are using a mutable variable. But drivers can't
  // be reused (i.e. once stopped they can't be started again. Thus,
  // we have to allocate a new driver before each run or after each stop.
  var driver: Option[SchedulerDriver] = None

  implicit val timeout: Timeout = 5.seconds

  protected def newTimer() = new Timer("marathonSchedulerTimer")

  def deploy(plan: DeploymentPlan, force: Boolean = false): Future[Unit] = {
    log.info(s"Deploy plan with force=$force:\n$plan ")
    val future: Future[Any] = PromiseActor.askWithoutTimeout(system, schedulerActor, Deploy(plan, force))
    future.map {
      case DeploymentStarted(_) => ()
      case CommandFailed(_, t)  => throw t
    }
  }

  def cancelDeployment(id: String): Unit =
    schedulerActor ! CancelDeployment(id)

  def listAppVersions(appId: PathId): Iterable[Timestamp] =
    Await.result(appRepository.listVersions(appId), config.zkTimeoutDuration)

  def listRunningDeployments(): Future[Seq[DeploymentStepInfo]] =
    (schedulerActor ? RetrieveRunningDeployments)
      .recoverWith {
        case _: TimeoutException =>
          Future.failed(new TimeoutException(s"Can not retrieve the list of running deployments in time"))
      }
      .mapTo[RunningDeployments]
      .map(_.plans)

  def getApp(appId: PathId, version: Timestamp): Option[AppDefinition] = {
    Await.result(appRepository.app(appId, version), config.zkTimeoutDuration)
  }

  def killTasks(
    appId: PathId,
    tasks: Iterable[Task]): Iterable[Task] = {
    schedulerActor ! KillTasks(appId, tasks.map(_.taskId))

    tasks
  }

  //Begin Service interface

  override def startUp(): Unit = {
    log.info("Starting up")
    super.startUp()
  }

  override def run(): Unit = {
    log.info("Beginning run")

    // The first thing we do is offer our leadership.
    electionService.offerLeadership(this)

    // Block on the latch which will be countdown only when shutdown has been
    // triggered. This is to prevent run()
    // from exiting.
    scala.concurrent.blocking {
      latch.await()
    }

    log.info("Completed run")
  }

  override def triggerShutdown(): Unit = synchronized {
    log.info("Shutdown triggered")

    stopDriver()

    log.info("Cancelling timer")
    timer.cancel()

    // The countdown latch blocks run() from exiting. Counting down the latch removes the block.
    log.info("Removing the blocking of run()")
    latch.countDown()

    super.triggerShutdown()
  }

  def stopDriver(): Unit = synchronized {
    log.info("Stopping driver")

    // Stopping the driver will cause the driver run() method to return.
    driver.foreach(_.stop(true)) // failover = true
    driver = None
  }

  //End Service interface

  //Begin ElectionCandidate interface

  def startLeadership(): Unit = synchronized {
    log.info("Elect leadership, running driver")

    // execute tasks, only the leader is allowed to
    migration.migrate()

    // start all leadership coordination actors
    Await.result(leadershipCoordinator.prepareForStart(), config.maxActorStartupTime().milliseconds)

    // start timers
    schedulePeriodicOperations()

    // create new driver
    driver = Some(driverFactory.createDriver())

    // The following block asynchronously runs the driver. Note that driver.run()
    // blocks until the driver has been stopped (or aborted).
    Future {
      scala.concurrent.blocking {
        driver.foreach(_.run())
      }
    } onComplete { result =>
      synchronized {
        driver = None

        log.info(s"Driver future completed with result=$result.")
        result match {
          case Failure(t) => log.error("Exception while running driver", t)
          case _          =>
        }

        // tell leader election that we step back, but want to be re-elected if isRunning is true.
        electionService.abdicateLeadership(error = result.isFailure, reoffer = latch.getCount > 0)
      }
    }
  }

  def stopLeadership(): Unit = synchronized {
    log.info("Defeat leadership")

    leadershipCoordinator.stop()

    val oldTimer = timer
    timer = newTimer()
    oldTimer.cancel()

    if (driver.isDefined) {
      // Our leadership has been defeated. Thus, stop the driver.
      // Note that abdication command will be ran upon driver shutdown which
      // will then offer leadership again.
      stopDriver()
    }
    else {
      electionService.offerLeadership(this)
    }
  }

  //End ElectionDelegate interface

  private def schedulePeriodicOperations(): Unit = synchronized {
    timer.schedule(
      new TimerTask {
        def run() {
          if (electionService.isLeader) {
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
          if (electionService.isLeader) {
            schedulerActor ! ReconcileTasks
            schedulerActor ! ReconcileHealthChecks
          }
          else log.info("Not leader therefore not reconciling tasks")
        }
      },
      reconciliationInitialDelay.toMillis,
      reconciliationInterval.toMillis
    )
  }
}
