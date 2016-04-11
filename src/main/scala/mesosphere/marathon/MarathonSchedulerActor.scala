package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.event.{ EventStream, LoggingReceive }
import akka.pattern.ask
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event.{ AppTerminatedEvent, DeploymentFailed, DeploymentSuccess, LocalLeadershipEvent }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.upgrade.{ UpgradeConfig, DeploymentManager, DeploymentPlan, TaskKillActor }
import mesosphere.mesos.protos
import org.apache.mesos.Protos.{ Status, TaskID }
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class LockingFailedException(msg: String) extends Exception(msg)

// scalastyle:off parameter.number
class MarathonSchedulerActor private (
    createSchedulerActions: ActorRef => SchedulerActions,
    deploymentManagerProps: SchedulerActions => Props,
    historyActorProps: Props,
    appRepository: AppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    launchQueue: LaunchQueue,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    leaderInfo: LeaderInfo,
    eventBus: EventStream,
    config: UpgradeConfig,
    cancellationTimeout: FiniteDuration = 1.minute) extends Actor with ActorLogging with Stash {
  import context.dispatcher
  import mesosphere.marathon.MarathonSchedulerActor._

  var lockedApps = Set.empty[PathId]
  var schedulerActions: SchedulerActions = _
  var deploymentManager: ActorRef = _
  var historyActor: ActorRef = _
  var activeReconciliation: Option[Future[_]] = None

  override def preStart(): Unit = {
    schedulerActions = createSchedulerActions(self)
    deploymentManager = context.actorOf(deploymentManagerProps(schedulerActions), "DeploymentManager")
    historyActor = context.actorOf(historyActorProps, "HistoryActor")

    leaderInfo.subscribe(self)
  }

  override def postStop(): Unit = {
    leaderInfo.unsubscribe(self)
  }

  def receive: Receive = suspended

  def suspended: Receive = LoggingReceive.withLabel("suspended"){
    case LocalLeadershipEvent.ElectedAsLeader =>
      log.info("Starting scheduler actor")
      deploymentRepository.all() onComplete {
        case Success(deployments) => self ! RecoverDeployments(deployments)
        case Failure(_)           => self ! RecoverDeployments(Nil)
      }

    case RecoverDeployments(deployments) =>
      deployments.foreach { plan =>
        log.info(s"Recovering deployment:\n$plan")
        deploy(context.system.deadLetters, Deploy(plan, force = false))
      }

      log.info("Scheduler actor ready")
      unstashAll()
      context.become(started)
      self ! ReconcileHealthChecks

    case LocalLeadershipEvent.Standby =>
    // ignore, FIXME: When we get this while recovering deployments, we become active

    case _                            => stash()
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def started: Receive = LoggingReceive.withLabel("started")(sharedHandlers orElse {
    case LocalLeadershipEvent.Standby =>
      log.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      deploymentManager ! CancelAllDeployments
      lockedApps = Set.empty
      context.become(suspended)

    case LocalLeadershipEvent.ElectedAsLeader => // ignore

    case ReconcileTasks =>
      import akka.pattern.pipe
      import context.dispatcher
      val reconcileFuture = activeReconciliation match {
        case None =>
          log.info("initiate task reconciliation")
          val newFuture = schedulerActions.reconcileTasks(driver)
          activeReconciliation = Some(newFuture)
          newFuture.onFailure {
            case NonFatal(e) => log.error(e, "error while reconciling tasks")
          }
          newFuture
            // the self notification MUST happen before informing the initiator
            // if we want to ensure that we trigger a new reconciliation for
            // the first call after the last ReconcileTasks.answer has been received.
            .andThen { case _ => self ! ReconcileFinished }
        case Some(active) =>
          log.info("task reconciliation still active, reusing result")
          active
      }
      reconcileFuture.map(_ => ReconcileTasks.answer).pipeTo(sender)

    case ReconcileFinished =>
      log.info("task reconciliation has finished")
      activeReconciliation = None

    case ReconcileHealthChecks =>
      schedulerActions.reconcileHealthChecks()

    case ScaleApps => schedulerActions.scaleApps()

    case cmd @ ScaleApp(appId) =>
      val origSender = sender()
      withLockFor(appId) {
        val res = schedulerActions.scale(driver, appId)

        if (origSender != context.system.deadLetters)
          res.sendAnswer(origSender, cmd)

        res andThen {
          case _ => self ! cmd.answer // unlock app
        }
      }

    case cmd: CancelDeployment =>
      deploymentManager forward cmd

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case cmd @ KillTasks(appId, taskIds) =>
      val origSender = sender()
      withLockFor(appId) {
        val promise = Promise[Unit]()
        context.actorOf(TaskKillActor.props(driver, appId, taskTracker, eventBus, taskIds, config, promise))
        val res = for {
          _ <- promise.future
          Some(app) <- appRepository.currentVersion(appId)
        } yield schedulerActions.scale(driver, app)

        res onComplete { _ =>
          self ! cmd.answer // unlock app
        }

        res.sendAnswer(origSender, cmd)
      }
  })

  /**
    * handlers for messages that unlock apps and to retrieve running deployments
    */
  def sharedHandlers: Receive = {
    case DeploymentFinished(plan) =>
      lockedApps --= plan.affectedApplicationIds
      deploymentSuccess(plan)

    case DeploymentManager.DeploymentFailed(plan, reason) =>
      lockedApps --= plan.affectedApplicationIds
      deploymentFailed(plan, reason)

    case AppScaled(id)         => lockedApps -= id

    case TasksKilled(appId, _) => lockedApps -= appId

    case RetrieveRunningDeployments =>
      deploymentManager forward RetrieveRunningDeployments
  }

  /**
    * Waits for all the apps affected by @plan to be unlocked
    * and starts @plan. If it receives a CancellationTimeoutExceeded
    * message, it will mark the deployment as failed and go into
    * the started state.
    *
    * @param plan The deployment plan we are trying to execute.
    * @param origSender The original sender of the Deploy message.
    * @return
    */
  def awaitCancellation(plan: DeploymentPlan, origSender: ActorRef, cancellationHandler: Cancellable): Receive =
    sharedHandlers.andThen[Unit] { _ =>
      if (tryDeploy(plan, origSender)) {
        cancellationHandler.cancel()
      }
    } orElse {
      case CancellationTimeoutExceeded =>
        val reason = new TimeoutException("Exceeded timeout for canceling conflicting deployments.")
        deploymentFailed(plan, reason)
        origSender ! CommandFailed(Deploy(plan, force = true), reason)
        unstashAll()
        context.become(started)

      case _ => stash()
    }

  /**
    * If all required apps are unlocked, start the deployment,
    * unstash all messages and put actor in started state
    *
    * @param plan The deployment plan that has been sent with force=true
    * @param origSender The original sender of the deployment
    */
  def tryDeploy(plan: DeploymentPlan, origSender: ActorRef): Boolean = {
    val affectedApps = plan.affectedApplicationIds
    if (!lockedApps.exists(affectedApps)) {
      deploy(origSender, Deploy(plan, force = false))
      unstashAll()
      context.become(started)
      true
    }
    else {
      false
    }
  }

  /**
    * Tries to acquire the lock for the given appId.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an AppLockedException.
    */
  def withLockFor[A](appIds: Set[PathId])(f: => A): Try[A] = {
    // there's no need for synchronization here, because this is being
    // executed inside an actor, i.e. single threaded
    val conflicts = lockedApps intersect appIds
    if (conflicts.isEmpty) {
      lockedApps ++= appIds
      Try(f)
    }
    else {
      Failure(new LockingFailedException("Failed to acquire locks."))
    }
  }

  /**
    * Tries to acquire the lock for the given appId.
    * If it succeeds it executes the given function,
    * otherwise the result will contain an AppLockedException.
    */
  def withLockFor[A](appId: PathId)(f: => A): Try[A] =
    withLockFor(Set(appId))(f)

  // there has to be a better way...
  def driver: SchedulerDriver = marathonSchedulerDriverHolder.driver.get

  def deploy(origSender: ActorRef, cmd: Deploy): Unit = {
    val plan = cmd.plan
    val ids = plan.affectedApplicationIds

    val res = withLockFor(ids) {
      deploy(driver, plan)
    }

    res match {
      case Success(_) =>
        if (origSender != Actor.noSender) origSender ! cmd.answer
      case Failure(e: LockingFailedException) if cmd.force =>
        deploymentManager ! CancelConflictingDeployments(plan)
        val cancellationHandler = context.system.scheduler.scheduleOnce(
          cancellationTimeout,
          self,
          CancellationTimeoutExceeded)

        context.become(awaitCancellation(plan, origSender, cancellationHandler))
      case Failure(e: LockingFailedException) =>
        deploymentManager.ask(RetrieveRunningDeployments)(2.seconds)
          .mapTo[RunningDeployments]
          .foreach {
            case RunningDeployments(plans) =>
              def intersectsWithNewPlan(existingPlan: DeploymentPlan): Boolean = {
                existingPlan.affectedApplicationIds.intersect(plan.affectedApplicationIds).nonEmpty
              }
              val relatedDeploymentIds: Seq[String] = plans.collect {
                case DeploymentStepInfo(p, _, _, _) if intersectsWithNewPlan(p) => p.id
              }
              origSender ! CommandFailed(cmd, AppLockedException(relatedDeploymentIds))
          }
    }
  }

  def deploy(driver: SchedulerDriver, plan: DeploymentPlan): Unit = {
    deploymentRepository.store(plan).foreach { _ =>
      deploymentManager ! PerformDeployment(driver, plan)
    }
  }

  def deploymentSuccess(plan: DeploymentPlan): Unit = {
    log.info(s"Deployment of ${plan.target.id} successful")
    eventBus.publish(DeploymentSuccess(plan.id, plan))
    deploymentRepository.expunge(plan.id)
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Unit = {
    log.error(reason, s"Deployment of ${plan.target.id} failed")
    plan.affectedApplicationIds.foreach(appId => launchQueue.purge(appId))
    eventBus.publish(DeploymentFailed(plan.id, plan))
    if (reason.isInstanceOf[DeploymentCanceledException])
      deploymentRepository.expunge(plan.id)
  }
}

object MarathonSchedulerActor {
  def props(
    createSchedulerActions: ActorRef => SchedulerActions,
    deploymentManagerProps: SchedulerActions => Props,
    historyActorProps: Props,
    appRepository: AppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    launchQueue: LaunchQueue,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    leaderInfo: LeaderInfo,
    eventBus: EventStream,
    config: UpgradeConfig,
    cancellationTimeout: FiniteDuration = 1.minute): Props = {
    Props(new MarathonSchedulerActor(
      createSchedulerActions,
      deploymentManagerProps,
      historyActorProps,
      appRepository,
      deploymentRepository,
      healthCheckManager,
      taskTracker,
      launchQueue,
      marathonSchedulerDriverHolder,
      leaderInfo,
      eventBus,
      config,
      cancellationTimeout
    ))
  }

  case class RecoverDeployments(deployments: Seq[DeploymentPlan])

  sealed trait Command {
    def answer: Event
  }

  case object ReconcileTasks extends Command {
    def answer: Event = TasksReconciled
  }

  private case object ReconcileFinished

  case object ReconcileHealthChecks

  case object ScaleApps

  case class ScaleApp(appId: PathId) extends Command {
    def answer: Event = AppScaled(appId)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class KillTasks(appId: PathId, taskIds: Iterable[Task.Id]) extends Command {
    def answer: Event = TasksKilled(appId, taskIds)
  }

  case object RetrieveRunningDeployments

  sealed trait Event
  case class AppScaled(appId: PathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class TasksKilled(appId: PathId, taskIds: Iterable[Task.Id]) extends Event

  case class RunningDeployments(plans: Seq[DeploymentStepInfo])

  case class CommandFailed(cmd: Command, reason: Throwable) extends Event

  case object CancellationTimeoutExceeded

  implicit class AnswerOps[A](val f: Future[A]) extends AnyVal {
    def sendAnswer(receiver: ActorRef, cmd: Command)(implicit ec: ExecutionContext): Future[A] = {
      f onComplete {
        case Success(_) =>
          receiver ! cmd.answer

        case Failure(t) =>
          receiver ! CommandFailed(cmd, t)
      }

      f
    }
  }
}

class SchedulerActions(
    appRepository: AppRepository,
    groupRepository: GroupRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    launchQueue: LaunchQueue,
    eventBus: EventStream,
    val schedulerActor: ActorRef,
    config: MarathonConf)(implicit ec: ExecutionContext) {
  import mesosphere.mesos.protos.Implicits._

  private[this] val log = LoggerFactory.getLogger(getClass)

  // TODO move stuff below out of the scheduler

  def startApp(driver: SchedulerDriver, app: AppDefinition): Unit = {
    log.info(s"Starting app ${app.id}")
    scale(driver, app)
  }

  def stopApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    healthCheckManager.removeAllFor(app.id)

    log.info(s"Stopping app ${app.id}")
    taskTracker.appTasks(app.id).map { tasks =>
      for (taskId <- tasks.iterator.flatMap(_.launchedMesosId)) {
        log.info(s"Killing task [${taskId.getValue}]")
        driver.killTask(taskId)
      }
      launchQueue.purge(app.id)
      launchQueue.resetDelay(app)
      // TODO after all tasks have been killed we should remove the app from taskTracker

      eventBus.publish(AppTerminatedEvent(app.id))
    }
  }

  def scaleApps(): Future[Unit] = {
    appRepository.allPathIds().map(_.toSet).andThen {
      case Success(appIds) => for (appId <- appIds) schedulerActor ! ScaleApp(appId)
      case Failure(t)      => log.warn("Failed to get task names", t)
    }.map(_ => ())
  }

  /**
    * Make sure all apps are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    *
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Future[Status] = {
    appRepository.allPathIds().map(_.toSet).flatMap { appIds =>
      taskTracker.tasksByApp().map { tasksByApp =>
        val knownTaskStatuses = appIds.flatMap { appId =>
          tasksByApp.appTasks(appId).flatMap(_.mesosStatus)
        }

        for (unknownAppId <- tasksByApp.allAppIdsWithTasks -- appIds) {
          log.warn(
            s"App $unknownAppId exists in TaskTracker, but not App store. " +
              "The app was likely terminated. Will now expunge."
          )
          for (orphanTask <- tasksByApp.marathonAppTasks(unknownAppId)) {
            log.info(s"Killing task ${orphanTask.getId}")
            driver.killTask(protos.TaskID(orphanTask.getId))
          }
        }

        log.info("Requesting task reconciliation with the Mesos master")
        log.debug(s"Tasks to reconcile: $knownTaskStatuses")
        if (knownTaskStatuses.nonEmpty)
          driver.reconcileTasks(knownTaskStatuses.asJava)

        // in addition to the known statuses send an empty list to get the unknown
        driver.reconcileTasks(java.util.Arrays.asList())
      }
    }
  }

  def reconcileHealthChecks(): Unit = {
    for {
      apps <- groupRepository.rootGroup().map(_.map(_.transitiveApps).getOrElse(Set.empty))
      app <- apps
    } healthCheckManager.reconcileWith(app.id)
  }

  /**
    * Ensures current application parameters (resource requirements, URLs,
    * command, and constraints) are applied consistently across running
    * application instances.
    */
  private def update(
    driver: SchedulerDriver,
    updatedApp: AppDefinition,
    appUpdate: AppUpdate): Unit = {
    // TODO: implement app instance restart logic
  }

  /**
    * Make sure the app is running the correct number of instances
    */
  def scale(driver: SchedulerDriver, app: AppDefinition): Unit = {
    val launchedCount = taskTracker.countLaunchedAppTasksSync(app.id)
    val targetCount = app.instances

    if (targetCount > launchedCount) {
      log.info(s"Need to scale ${app.id} from $launchedCount up to $targetCount instances")

      val queuedOrRunning = launchQueue.get(app.id).map(_.finalTaskCount).getOrElse(launchedCount)
      val toQueue = targetCount - queuedOrRunning

      if (toQueue > 0) {
        log.info(s"Queueing $toQueue new tasks for ${app.id} ($queuedOrRunning queued or running)")
        launchQueue.add(app, toQueue)
      }
      else {
        log.info(s"Already queued or started $queuedOrRunning tasks for ${app.id}. Not scaling.")
      }
    }
    else if (targetCount < launchedCount) {
      log.info(s"Scaling ${app.id} from $launchedCount down to $targetCount instances")
      launchQueue.purge(app.id)

      val toKill = taskTracker.appTasksLaunchedSync(app.id).take(launchedCount - targetCount)
      val taskIds: Iterable[TaskID] = toKill.flatMap(_.launchedMesosId)
      log.info(s"Killing tasks: ${taskIds.map(_.getValue)}")
      for (taskId <- taskIds) {
        driver.killTask(taskId)
      }
    }
    else {
      log.info(s"Already running ${app.instances} instances of ${app.id}. Not scaling.")
    }
  }

  def scale(driver: SchedulerDriver, appId: PathId): Future[Unit] = {
    currentAppVersion(appId).map {
      case Some(app) => scale(driver, app)
      case _         => log.warn(s"App $appId does not exist. Not scaling.")
    }
  }

  def currentAppVersion(appId: PathId): Future[Option[AppDefinition]] =
    appRepository.currentVersion(appId)
}
