package mesosphere.marathon

import java.util.concurrent.CountDownLatch
import java.util.{ Timer, TimerTask }
import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import com.google.common.util.concurrent.AbstractExecutionThreadService
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.election.{ ElectionCandidate, ElectionService }
import mesosphere.marathon.core.heartbeat._
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Migration, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentManager.{ CancelDeployment, DeploymentStepInfo }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.PromiseActor
import mesosphere.util.state.FrameworkIdUtil
import org.apache.mesos.Protos.FrameworkID
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, TimeoutException }
import scala.util.Failure

/**
  * PrePostDriverCallback is implemented by callback receivers which have to listen for driver
  * start/stop events
  */
trait PrePostDriverCallback {
  /**
    * Will get called _before_ the driver is running, but after migration.
    */
  def preDriverStarts: Future[Unit]

  /**
    * Will get called _after_ the driver terminated
    */
  def postDriverTerminates: Future[Unit]
}

/**
  * DeploymentService provides methods to deploy plans.
  */
trait DeploymentService {
  /**
    * Deploy a plan.
    * @param plan the plan to deploy.
    * @param force only one deployment can be applied at a time. With this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return a failed future if the deployment failed.
    */
  def deploy(plan: DeploymentPlan, force: Boolean = false): Future[Unit]
}

/**
  * Wrapper class for the scheduler
  */
class MarathonSchedulerService @Inject() (
  leadershipCoordinator: LeadershipCoordinator,
  healthCheckManager: HealthCheckManager,
  config: MarathonConf,
  frameworkIdUtil: FrameworkIdUtil,
  electionService: ElectionService,
  prePostDriverCallbacks: Seq[PrePostDriverCallback],
  appRepository: AppRepository,
  driverFactory: SchedulerDriverFactory,
  system: ActorSystem,
  migration: Migration,
  @Named("schedulerActor") schedulerActor: ActorRef,
  @Named(ModuleNames.MESOS_HEARTBEAT_ACTOR) mesosHeartbeatActor: ActorRef,
  metrics: Metrics = new Metrics(new MetricRegistry))
    extends AbstractExecutionThreadService with ElectionCandidate with DeploymentService {

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val zkTimeout = config.zkTimeoutDuration

  val isRunningLatch = new CountDownLatch(1)

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
      case CommandFailed(_, t) => throw t
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
    tasks: Iterable[Task]): Unit = {
    schedulerActor ! KillTasks(appId, tasks)
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
      isRunningLatch.await()
    }

    log.info("Completed run")
  }

  override def triggerShutdown(): Unit = synchronized {
    log.info("Shutdown triggered")

    electionService.abdicateLeadership(reoffer = false)
    stopDriver()

    log.info("Cancelling timer")
    timer.cancel()

    // The countdown latch blocks run() from exiting. Counting down the latch removes the block.
    log.info("Removing the blocking of run()")
    isRunningLatch.countDown()

    super.triggerShutdown()
  }

  private[this] def stopDriver(): Unit = synchronized {
    // many are the assumptions concerning when this is invoked. see startLeadership, stopLeadership,
    // triggerShutdown.
    log.info("Stopping driver")

    // Stopping the driver will cause the driver run() method to return.
    driver.foreach(_.stop(true)) // failover = true

    // signals that the driver was stopped manually (as opposed to crashing mid-process)
    driver = None
  }

  //End Service interface

  //Begin ElectionCandidate interface

  override def startLeadership(): Unit = synchronized {
    log.info("As new leader running the driver")

    // execute tasks, only the leader is allowed to
    migration.migrate()

    // run all pre-driver callbacks
    log.info(s"""Call preDriverStarts callbacks on ${prePostDriverCallbacks.mkString(", ")}""")
    Await.result(
      Future.sequence(prePostDriverCallbacks.map(_.preDriverStarts)),
      config.onElectedPrepareTimeout().millis
    )
    log.info(s"Finished preDriverStarts callbacks")

    // start all leadership coordination actors
    Await.result(leadershipCoordinator.prepareForStart(), config.maxActorStartupTime().milliseconds)

    // create new driver
    driver = Some(driverFactory.createDriver())

    // start timers
    schedulePeriodicOperations()

    // The following block asynchronously runs the driver. Note that driver.run()
    // blocks until the driver has been stopped (or aborted).
    Future {
      scala.concurrent.blocking {
        driver.foreach(_.run())
      }
    } onComplete { result =>
      synchronized {

        log.info(s"Driver future completed with result=$result.")
        result match {
          case Failure(t) => log.error("Exception while running driver", t)
          case _ =>
        }

        // ONLY do this if there's some sort of driver crash: avoid invoking abdication logic if
        // the driver was stopped via stopDriver. stopDriver only happens when
        //   1. we're being terminated (and have already abdicated)
        //   2. we've lost leadership (no need to abdicate if we've already lost)
        driver.foreach { _ =>
          // tell leader election that we step back, but want to be re-elected if isRunning is true.
          electionService.abdicateLeadership(error = result.isFailure, reoffer = isRunningLatch.getCount > 0)
        }

        driver = None

        log.info(s"Call postDriverRuns callbacks on ${prePostDriverCallbacks.mkString(", ")}")
        Await.result(Future.sequence(prePostDriverCallbacks.map(_.postDriverTerminates)), config.zkTimeoutDuration)
        log.info(s"Finished postDriverRuns callbacks")
      }
    }
  }

  override def stopLeadership(): Unit = synchronized {
    // invoked by election service upon loss of leadership (state transitioned to Idle)
    log.info("Lost leadership")

    leadershipCoordinator.stop()

    val oldTimer = timer
    timer = newTimer()
    oldTimer.cancel()

    driver.foreach { driverInstance =>
      mesosHeartbeatActor ! Heartbeat.MessageDeactivate(MesosHeartbeatMonitor.sessionOf(driverInstance))
      // Our leadership has been defeated. Thus, stop the driver.
      stopDriver()
    }
    // Abdication will have already happened if the driver terminated abnormally.
    // Otherwise we've either been terminated or have lost leadership for some other reason (network part?)
    if (isRunningLatch.getCount > 0) {
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
          } else log.info("Not leader therefore not scaling apps")
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
          } else log.info("Not leader therefore not reconciling tasks")
        }
      },
      reconciliationInitialDelay.toMillis,
      reconciliationInterval.toMillis
    )
  }
}
