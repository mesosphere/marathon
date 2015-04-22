package mesosphere.marathon

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.event.EventStream
import akka.pattern.ask
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.event.{ AppTerminatedEvent, DeploymentFailed, DeploymentSuccess, HistoryActor }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan, TaskKillActor }
import mesosphere.mesos.protos.Offer
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.{ TaskBuilder, protos }
import org.apache.mesos.Protos.{ TaskID, TaskInfo, TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

class LockingFailedException(msg: String) extends Exception(msg)

class MarathonSchedulerActor(
    mapper: ObjectMapper,
    appRepository: AppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    taskIdUtil: TaskIdUtil,
    storage: StorageProvider,
    eventBus: EventStream,
    taskFailureRepository: TaskFailureRepository,
    config: MarathonConf) extends Actor with ActorLogging with Stash {
  import context.dispatcher
  import mesosphere.marathon.MarathonSchedulerActor._

  var lockedApps = Set.empty[PathId]
  var scheduler: SchedulerActions = _
  var deploymentManager: ActorRef = _
  var historyActor: ActorRef = _

  val cancellationTimeout: FiniteDuration = 1.minute

  override def preStart(): Unit = {

    scheduler = new SchedulerActions(
      mapper,
      appRepository,
      healthCheckManager,
      taskTracker,
      taskIdUtil,
      taskQueue,
      eventBus,
      self,
      config)

    deploymentManager = context.actorOf(
      Props(
        classOf[DeploymentManager],
        appRepository,
        taskTracker,
        taskQueue,
        scheduler,
        storage,
        healthCheckManager,
        eventBus
      ),
      "UpgradeManager"
    )

    historyActor = context.actorOf(
      Props(classOf[HistoryActor], eventBus, taskFailureRepository), "HistoryActor")
  }

  def receive: Receive = suspended

  def suspended: Receive = {
    case Start =>
      log.info("Starting scheduler actor")
      deploymentRepository.all() onComplete {
        case Success(deployments) => self ! RecoverDeployments(deployments)
        case Failure(_)           => self ! RecoverDeployments(Nil)
      }

    case RecoverDeployments(deployments) =>
      deployments.foreach { plan =>
        log.info(s"Recovering deployment: $plan")
        deploy(context.system.deadLetters, Deploy(plan, force = false))
      }

      log.info("Scheduler actor ready")
      unstashAll()
      context.become(started)
      self ! ReconcileHealthChecks

    case Suspend(_) => // ignore

    case _          => stash()
  }

  def started: Receive = sharedHandlers orElse {
    case Suspend(t) =>
      log.info("Suspending scheduler actor")
      healthCheckManager.removeAll()
      deploymentManager ! CancelAllDeployments
      lockedApps = Set.empty
      context.become(suspended)

    case Start => // ignore

    case ReconcileTasks =>
      scheduler.reconcileTasks(driver)
      sender ! ReconcileTasks.answer

    case ReconcileHealthChecks =>
      scheduler.reconcileHealthChecks()

    case ScaleApps => scheduler.scaleApps()

    case cmd @ ScaleApp(appId) =>
      val origSender = sender()
      withLockFor(appId) {
        val res = scheduler.scale(driver, appId)

        if (origSender != context.system.deadLetters)
          res.sendAnswer(origSender, cmd)

        res andThen {
          case _ => self ! cmd.answer
        }
      }

    case cmd: CancelDeployment =>
      deploymentManager forward cmd

    case cmd @ Deploy(plan, force) =>
      deploy(sender(), cmd)

    case cmd @ KillTasks(appId, taskIds, scale) =>
      val origSender = sender()
      withLockFor(appId) {
        val promise = Promise[Unit]()
        val tasksToKill = taskIds.flatMap(taskTracker.fetchTask(appId, _)).toSet
        context.actorOf(Props(classOf[TaskKillActor], driver, appId, taskTracker, eventBus, tasksToKill, promise))
        val res = if (scale) {
          for {
            _ <- promise.future
            currentApp <- appRepository.currentVersion(appId)
            _ <- currentApp
              .map { app => appRepository.store(app.copy(instances = app.instances - tasksToKill.size)) }
              .getOrElse(Future.successful(()))
          } yield ()
        }
        else {
          for {
            _ <- promise.future
            Some(app) <- appRepository.currentVersion(appId)
          } yield scheduler.scale(driver, app)
        }

        res onComplete { _ =>
          self ! cmd.answer
        }

        res.sendAnswer(origSender, cmd)
      }
  }

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
              val relatedDeploymentIds: Seq[String] = plans.collect {
                case (p, _) if p.affectedApplicationIds.intersect(plan.affectedApplicationIds).nonEmpty => p.id
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
    eventBus.publish(DeploymentSuccess(plan.id))
    deploymentRepository.expunge(plan.id)
  }

  def deploymentFailed(plan: DeploymentPlan, reason: Throwable): Unit = {
    log.error(reason, s"Deployment of ${plan.target.id} failed")
    plan.affectedApplicationIds.foreach(appId => taskQueue.purge(appId))
    eventBus.publish(DeploymentFailed(plan.id))
    if (reason.isInstanceOf[DeploymentCanceledException])
      deploymentRepository.expunge(plan.id)
  }
}

object MarathonSchedulerActor {
  case object Start
  case class Suspend(reason: Throwable)

  case class RecoverDeployments(deployments: Seq[DeploymentPlan])

  sealed trait Command {
    def answer: Event
  }

  case object ReconcileTasks extends Command {
    def answer: Event = TasksReconciled
  }

  case object ReconcileHealthChecks

  case object ScaleApps

  case class ScaleApp(appId: PathId) extends Command {
    def answer: Event = AppScaled(appId)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class KillTasks(appId: PathId, taskIds: Set[String], scale: Boolean) extends Command {
    def answer: Event = TasksKilled(appId, taskIds)
  }

  case object RetrieveRunningDeployments

  sealed trait Event
  case class AppScaled(appId: PathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class TasksKilled(appId: PathId, taskIds: Set[String]) extends Event

  case class RunningDeployments(plans: Seq[(DeploymentPlan, DeploymentStepInfo)])

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
    mapper: ObjectMapper,
    appRepository: AppRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskIdUtil: TaskIdUtil,
    taskQueue: TaskQueue,
    eventBus: EventStream,
    val schedulerActor: ActorRef,
    config: MarathonConf)(implicit ec: ExecutionContext) {
  import mesosphere.mesos.protos.Implicits._

  private[this] val log = LoggerFactory.getLogger(getClass)

  // TODO move stuff below out of the scheduler

  def startApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    currentAppVersion(app.id).flatMap { appOption =>
      require(appOption.isEmpty, s"Already started app '${app.id}'")

      appRepository.store(app).map { _ =>
        log.info(s"Starting app ${app.id}")
        scale(driver, app)
      }
    }
  }

  def stopApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    appRepository.expunge(app.id).map { successes =>
      if (!successes.forall(_ == true)) {
        throw new StorageException(s"Error expunging ${app.id}")
      }

      healthCheckManager.removeAllFor(app.id)

      log.info(s"Stopping app ${app.id}")
      val tasks = taskTracker.get(app.id)

      for (task <- tasks) {
        log.info(s"Killing task ${task.getId}")
        driver.killTask(protos.TaskID(task.getId))
      }
      taskQueue.purge(app.id)
      taskTracker.shutdown(app.id)
      taskQueue.rateLimiter.resetDelay(app)
      // TODO after all tasks have been killed we should remove the app from taskTracker

      eventBus.publish(AppTerminatedEvent(app.id))
    }
  }

  def scaleApps(): Future[Unit] = {
    appRepository.allPathIds().map(_.toSet).andThen {
      case Success(appIds) => { for (appId <- appIds) schedulerActor ! ScaleApp(appId) }
      case Failure(t)      => log.warn("Failed to get task names", t)
    }.map(_ => ())
  }

  /**
    * Make sure all apps are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Future[Unit] = {
    appRepository.allPathIds().map(_.toSet).andThen {
      case Success(appIds) =>
        log.info("Syncing tasks for all apps")

        val knownTaskStatuses = appIds.flatMap { appId =>
          taskTracker.get(appId).collect {
            case task if task.hasStatus => task.getStatus
            case task => // staged tasks, which have no status yet
              TaskStatus.newBuilder
                .setState(TaskState.TASK_STAGING)
                .setTaskId(TaskID.newBuilder.setValue(task.getId))
                .build()
          }
        }

        for (unknownAppId <- taskTracker.list.keySet -- appIds) {
          log.warn(
            s"App $unknownAppId exists in TaskTracker, but not App store. " +
              "The app was likely terminated. Will now expunge."
          )
          for (orphanTask <- taskTracker.get(unknownAppId)) {
            log.info(s"Killing task ${orphanTask.getId}")
            driver.killTask(protos.TaskID(orphanTask.getId))
          }
          taskTracker.shutdown(unknownAppId)
        }

        log.info("Requesting task reconciliation with the Mesos master")
        log.debug(s"Tasks to reconcile: $knownTaskStatuses")
        if (knownTaskStatuses.nonEmpty)
          driver.reconcileTasks(knownTaskStatuses.asJava)

        // in addition to the known statuses send an empty list to get the unknown
        driver.reconcileTasks(java.util.Arrays.asList())

      case Failure(t) =>
        log.warn("Failed to get task names", t)
    }.map(_ => ())
  }

  def reconcileHealthChecks(): Unit =
    for {
      apps <- appRepository.apps()
      app <- apps
    } healthCheckManager.reconcileWith(app.id)

  private def newTask(app: AppDefinition,
                      offer: Offer): Option[(TaskInfo, Seq[Long])] = {
    // TODO this should return a MarathonTask
    val builder = new TaskBuilder(
      app,
      taskIdUtil.newTaskId,
      taskTracker,
      config,
      mapper
    )

    builder.buildIfMatches(offer) map {
      case (task, ports) =>
        val taskBuilder = task.toBuilder
        taskBuilder.build -> ports
    }
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
    val currentCount = taskTracker.count(app.id)
    val targetCount = app.instances

    if (targetCount > currentCount) {
      log.info(s"Need to scale ${app.id} from $currentCount up to $targetCount instances")

      val queuedCount = taskQueue.count(app.id)
      val toQueue = targetCount - (currentCount + queuedCount)

      if (toQueue > 0) {
        log.info(s"Queueing $toQueue new tasks for ${app.id} ($queuedCount queued)")
        taskQueue.add(app, toQueue)
      }
      else {
        log.info(s"Already queued $queuedCount tasks for ${app.id}. Not scaling.")
      }
    }
    else if (targetCount < currentCount) {
      log.info(s"Scaling ${app.id} from $currentCount down to $targetCount instances")
      taskQueue.purge(app.id)

      val toKill = taskTracker.take(app.id, currentCount - targetCount)
      log.info(s"Killing tasks: ${toKill.map(_.getId)}")
      for (task <- toKill) {
        driver.killTask(protos.TaskID(task.getId))
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

  def updateApp(
    driver: SchedulerDriver,
    id: PathId,
    appUpdate: AppUpdate): Future[AppDefinition] = {
    appRepository.currentVersion(id).flatMap {
      case Some(currentVersion) =>
        val updatedApp = appUpdate(currentVersion)

        taskQueue.purge(id)

        appRepository.store(updatedApp).map { _ =>
          update(driver, updatedApp, appUpdate)
          healthCheckManager.reconcileWith(id)
          updatedApp
        }
      case _ => throw new UnknownAppException(id)
    }
  }

  def currentAppVersion(appId: PathId): Future[Option[AppDefinition]] =
    appRepository.currentVersion(appId)
}
