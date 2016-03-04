package mesosphere.marathon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ Timer, TimerTask }
import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import akka.pattern.{ after, ask }
import akka.util.Timeout
import com.codahale.metrics.Gauge
import com.google.common.util.concurrent.AbstractExecutionThreadService
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import com.twitter.common.zookeeper.Group.JoinException
import mesosphere.marathon.MarathonSchedulerActor._
import mesosphere.marathon.core.leadership.LeadershipCoordinator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.event.{ EventModule, LocalLeadershipEvent }
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
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

/**
  * Leadership callbacks.
  */
trait LeadershipCallback {

  /**
    * Will get called _before_ the scheduler driver is started.
    */
  def onElected: Future[Unit]

  /**
    * Will get called after leadership is abdicated.
    */
  def onDefeated: Future[Unit]
}

/**
  * Minimal trait to abdicate leadership from external components (e.g. zk connection listener)
  */
trait LeadershipAbdication {
  def abdicateLeadership(): Unit
}

/**
  * Wrapper class for the scheduler
  */
class MarathonSchedulerService @Inject() (
  leadershipCoordinator: LeadershipCoordinator,
  healthCheckManager: HealthCheckManager,
  @Named(ModuleNames.CANDIDATE) candidate: Option[Candidate],
  config: MarathonConf,
  frameworkIdUtil: FrameworkIdUtil,
  @Named(ModuleNames.LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
  appRepository: AppRepository,
  driverFactory: SchedulerDriverFactory,
  system: ActorSystem,
  migration: Migration,
  @Named("schedulerActor") schedulerActor: ActorRef,
  @Named(EventModule.busName) eventStream: EventStream,
  leadershipCallbacks: Seq[LeadershipCallback] = Seq.empty,
  metrics: Metrics = new Metrics(new MetricRegistry))
    extends AbstractExecutionThreadService with Leader with LeadershipAbdication {

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

    // The first thing we do is offer our leadership. If using ZooKeeper for
    // leadership election then we will wait to be elected. If we aren't (i.e.
    // no HA) then we take over leadership run the driver immediately.
    offerLeadership()

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

    leader.set(false)

    stopDriver()

    log.info("Cancelling timer")
    timer.cancel()

    log.info("Removing the blocking of run()")

    // The countdown latch blocks run() from exiting. Counting down the latch removes the block.
    latch.countDown()

    super.triggerShutdown()
  }

  def runDriver(abdicateCmdOption: Option[ExceptionalCommand[JoinException]]): Unit = synchronized {

    def executeAbdicationCommand() = abdicateCmdOption match {
      case Some(cmd) => cmd.execute()
      case _         => leader.set(false)
    }

    log.info("Running driver")

    // The following block asynchronously runs the driver. Note that driver.run()
    // blocks until the driver has been stopped (or aborted).
    Future {
      scala.concurrent.blocking {
        driver.foreach(_.run())
      }
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
        executeAbdicationCommand()

        // If we are shutting down then don't offer leadership. But if we
        // aren't then the driver was stopped via external means. For example,
        // our leadership could have been defeated or perhaps it was
        // abdicated. Therefore, for these cases we offer our leadership again.
        if (isRunning) {
          offerLeadership()
        }
      case Failure(t) =>
        log.error("Exception while running driver", t)
        abdicateAfterFailure(() => executeAbdicationCommand(), runAbdicationCommand = true)
    }
  }

  def stopDriver(): Unit = synchronized {
    log.info("Stopping driver")

    // Stopping the driver will cause the driver run() method to return.
    driver.foreach(_.stop(true)) // failover = true
    driver = None
  }

  //End Service interface

  //Begin Leader interface, which is required for CandidateImpl.
  override def onDefeated(): Unit = synchronized {
    log.info("Defeated (Leader Interface)")

    log.info(s"Call onDefeated leadership callbacks on ${leadershipCallbacks.mkString(", ")}")
    Await.result(Future.sequence(leadershipCallbacks.map(_.onDefeated)), zkTimeout)
    log.info(s"Finished onDefeated leadership callbacks")

    // Our leadership has been defeated and thus we call the defeatLeadership() method.
    defeatLeadership()
  }

  override def onElected(abdicateCmd: ExceptionalCommand[JoinException]): Unit = synchronized {
    var driverHandlesAbdication = false
    try {
      log.info("Elected (Leader Interface)")

      //execute tasks, only the leader is allowed to
      migration.migrate()

      //run all leadership callbacks
      log.info(s"""Call onElected leadership callbacks on ${leadershipCallbacks.mkString(", ")}""")
      Await.result(Future.sequence(leadershipCallbacks.map(_.onElected)), config.onElectedPrepareTimeout().millis)
      log.info(s"Finished onElected leadership callbacks")

      //start all leadership coordination actors
      Await.result(leadershipCoordinator.prepareForStart(), config.maxActorStartupTime().milliseconds)

      //create new driver
      driver = Some(driverFactory.createDriver())

      // We have been elected. Thus, elect leadership with the abdication command.
      electLeadership(Some(abdicateCmd))

      // The driver is created and running - now he is responsible for abdication handling
      driverHandlesAbdication = true

      // We successfully took over leadership. Time to reset backoff
      resetOfferLeadershipBackOff()

      // Start the leader duration metric
      startLeaderDurationMetric()
    }
    catch {
      case NonFatal(e) => // catch Scala and Java exceptions
        log.error("Failed to take over leadership", e)
        abdicateAfterFailure(() => abdicateCmd.execute(), runAbdicationCommand = !driverHandlesAbdication)
    }
  }
  //End Leader interface

  private def defeatLeadership(): Unit = synchronized {
    log.info("Defeat leadership")

    eventStream.publish(LocalLeadershipEvent.Standby)

    val oldTimer = timer
    timer = newTimer()
    oldTimer.cancel()

    // Our leadership has been defeated. Thus, update leadership and stop the driver.
    // Note that abdication command will be ran upon driver shutdown.
    leader.set(false)
    stopDriver()
    stopLeaderDurationMetric()
  }

  private def electLeadership(abdicateOption: Option[ExceptionalCommand[JoinException]]): Unit = synchronized {
    log.info("Elect leadership")

    // We have been elected as leader. Thus, update leadership and run the driver.
    leader.set(true)
    runDriver(abdicateOption)

    eventStream.publish(LocalLeadershipEvent.ElectedAsLeader)

    // Start the timer
    schedulePeriodicOperations()
  }

  def abdicateLeadership(): Unit = synchronized {
    if (leader.get()) {
      log.info("Abdicating")

      leadershipCoordinator.stop()

      // To abdicate we defeat our leadership
      defeatLeadership()
    }
  }

  lazy val initialOfferLeadershipBackOff = 0.5.seconds

  var offerLeadershipBackOff = initialOfferLeadershipBackOff
  val maximumOfferLeadershipBackOff = initialOfferLeadershipBackOff * 32

  private def increaseOfferLeadershipBackOff(): Unit = synchronized {
    if (offerLeadershipBackOff <= maximumOfferLeadershipBackOff) {
      offerLeadershipBackOff *= 2
      log.info(s"Increasing offerLeadership backoff to $offerLeadershipBackOff")
    }
  }

  private def resetOfferLeadershipBackOff(): Unit = synchronized {
    log.info("Reset offerLeadership backoff")
    offerLeadershipBackOff = initialOfferLeadershipBackOff
  }

  private def offerLeadership(): Unit = synchronized {
    log.info(s"Will offer leadership after $offerLeadershipBackOff backoff")
    after(offerLeadershipBackOff, system.scheduler)(Future {
      candidate.synchronized {
        candidate match {
          case Some(c) =>
            // In this case we care using ZooKeeper for leadership candidacy.
            // Thus, offer our leadership.
            log.info("Using HA and therefore offering leadership")
            c.offerLeadership(this)
          case _ =>
            // In this case we aren't using ZooKeeper for leadership election.
            // Thus, we simply elect ourselves as leader.
            log.info("Not using HA and therefore electing as leader by default")
            electLeadership(None)
        }
      }
    })
  }

  private def schedulePeriodicOperations(): Unit = synchronized {

    timer.schedule(
      new TimerTask {
        def run() {
          if (leader.get()) {
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
          if (leader.get()) {
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

  private def abdicateAfterFailure(abdicationCommand: () => Unit, runAbdicationCommand: Boolean): Unit = synchronized {

    increaseOfferLeadershipBackOff()

    abdicateLeadership()

    // here the driver is not running yet and therefore it cannot execute
    // the abdication command and offer the leadership. So we do it here
    if (runAbdicationCommand) {
      abdicationCommand()
      offerLeadership()
    }
  }

  private def startLeaderDurationMetric() = {
    metrics.gauge("service.mesosphere.marathon.leaderDuration", new Gauge[Long] {
      val startedAt = System.currentTimeMillis()

      override def getValue: Long =
        {
          System.currentTimeMillis() - startedAt
        }
    })
  }
  private def stopLeaderDurationMetric() = {
    metrics.registry.remove("service.mesosphere.marathon.leaderDuration")
  }
}
