package mesosphere.marathon

import akka.actor._
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.{ Promise, ExecutionContext, Future }
import mesosphere.mesos.protos
import mesosphere.marathon.api.v2.AppUpdate
import scala.collection.mutable
import org.apache.mesos.Protos.{ TaskInfo, TaskStatus }
import mesosphere.marathon.state.{ PathId, AppRepository }
import mesosphere.util.{ PromiseActor, LockManager, RateLimiters }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.{ DeploymentPlan, AppUpgradeManager }
import akka.event.EventStream
import mesosphere.mesos.util.FrameworkIdUtil
import scala.util.Failure
import mesosphere.marathon.event.{ DeploymentFailed, DeploymentSuccess, RestartFailed, RestartSuccess }
import mesosphere.marathon.upgrade.AppUpgradeManager.{ PerformDeployment, CancelDeployment, CancelUpgrade, Upgrade }
import scala.util.Success
import scala.collection.JavaConverters._
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import org.apache.mesos.Protos.OfferID
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import mesosphere.marathon.upgrade.DeploymentActor.{ Failed, Finished }

class MarathonSchedulerActor(
    val appRepository: AppRepository,
    val healthCheckManager: HealthCheckManager,
    val taskTracker: TaskTracker,
    val taskQueue: TaskQueue,
    val frameworkIdUtil: FrameworkIdUtil,
    val rateLimiters: RateLimiters,
    val eventBus: EventStream) extends Actor with ActorLogging {
  import context.dispatcher
  import MarathonSchedulerActor._

  val appLocks = LockManager[PathId]()
  var scheduler: SchedulerActions = _

  var upgradeManager: ActorRef = _

  override def preStart(): Unit = {

    scheduler = new SchedulerActions(
      appRepository,
      rateLimiters,
      healthCheckManager,
      taskTracker,
      taskQueue,
      eventBus,
      self)

    upgradeManager = context.actorOf(
      Props(classOf[AppUpgradeManager], appRepository, taskTracker, taskQueue, scheduler, eventBus), "UpgradeManager")

  }

  def receive = {
    case cmd @ StartApp(app) =>
      val origSender = sender
      locking(app.id, origSender, cmd, blocking = false) {
        scheduler.startApp(driver, app).sendAnswer(origSender, cmd)
      }

    case cmd @ StopApp(app) =>
      val origSender = sender
      upgradeManager ! CancelUpgrade(app.id, new AppDeletedException("The app has been deleted"))
      locking(app.id, origSender, cmd, blocking = true) {
        scheduler.stopApp(driver, app).sendAnswer(origSender, cmd)
      }

    case cmd @ UpdateApp(appId, update) =>
      val origSender = sender
      locking(appId, origSender, cmd, blocking = false) {
        scheduler.updateApp(driver, appId, update).sendAnswer(origSender, cmd)
      }

    case cmd @ UpgradeApp(app, keepAlive, maxRunning, false) =>
      val origSender = sender
      locking(app.id, origSender, cmd, blocking = false) {
        upgradeApp(driver, app, keepAlive, maxRunning).sendAnswer(origSender, cmd)
      }

    case cmd @ UpgradeApp(app, keepAlive, maxRunning, true) =>
      val origSender = sender
      upgradeManager ! CancelUpgrade(app.id, new TaskUpgradeCanceledException("The upgrade has been cancelled"))
      locking(app.id, origSender, cmd, blocking = true) {
        upgradeApp(driver, app, keepAlive, maxRunning).sendAnswer(origSender, cmd)
      }

    case cmd @ ReconcileTasks =>
      scheduler.reconcileTasks(driver)
      sender ! cmd.answer

    case cmd @ ScaleApp(appId) =>
      val origSender = sender
      locking(appId, origSender, cmd, blocking = false) {
        scheduler.scale(driver, appId).sendAnswer(origSender, cmd)
      }

    case cmd @ LaunchTasks(offers, tasks) =>
      driver.launchTasks(offers.asJava, tasks.asJava)
      sender ! cmd.answer

    case cmd @ Deploy(plan, false) =>
      // add locking
      val origSender = sender
      val ids = for {
        step <- plan.steps
        action <- step.actions
      } yield action.app.id

      locking(ids.distinct, origSender, cmd, blocking = false) {
        deploy(driver, plan).sendAnswer(origSender, cmd)
      }

    case cmd @ Deploy(plan, true) =>
      // add locking
      val origSender = sender
      val ids = distinctIds(plan)

      upgradeManager ! CancelDeployment(plan.target.id, new DeploymentCanceledException("The upgrade has been cancelled"))
      locking(ids, origSender, cmd, blocking = true) {
        deploy(driver, plan).sendAnswer(origSender, cmd)
      }
  }

  /**
    * Tries to acquire the lock for all given appIds.
    * If it succeeds it executes the given function,
    * otherwise a [CommandFailed] message is sent to
    * the original sender.
    * @param appIds
    * @param origSender
    * @param cmd
    * @param f
    * @tparam U
    * @return
    */
  def locking[U](appIds: Seq[PathId], origSender: ActorRef, cmd: Command, blocking: Boolean)(f: => Future[U]): Unit = {
    val locks = for {
      appId <- appIds
    } yield appLocks.get(appId)

    if (blocking) {
      locks.foreach(_.acquire())
      log.debug(s"Acquired locks for $appIds, performing cmd: $cmd")
      f andThen {
        case _ =>
          locks.foreach(_.release())
      }
    }
    else {
      val acquiredLocks = locks.takeWhile(_.tryAcquire(1000, TimeUnit.MILLISECONDS))
      if (acquiredLocks.size == locks.size) {
        log.debug(s"Acquired locks for $appIds, performing cmd: $cmd")
        f andThen {
          case _ =>
            acquiredLocks.foreach(_.release())
        }
      }
      else {
        log.debug(s"Failed to acquire some of the locks for $appIds to perform cmd: $cmd")
        acquiredLocks.foreach(_.release())
        origSender ! CommandFailed(cmd, new AppLockedException)
      }
    }
  }

  /**
    * Tries to acquire the lock for the given appId.
    * If it succeeds it executes the given function,
    * otherwise a [CommandFailed] message is sent to
    * the original sender.
    * @param appId
    * @param origSender
    * @param cmd
    * @param f
    * @tparam U
    * @return
    */
  def locking[U](appId: PathId, origSender: ActorRef, cmd: Command, blocking: Boolean)(f: => Future[U]): Unit =
    locking(Seq(appId), origSender, cmd, blocking)(f)

  // there has to be a better way...
  def driver: SchedulerDriver = MarathonSchedulerDriver.driver.get

  def upgradeApp(
    driver: SchedulerDriver,
    app: AppDefinition,
    keepAlive: Int,
    maxRunning: Option[Int]): Future[Boolean] = {
    appRepository.store(app) flatMap { appDef =>
      val promise = Promise[Any]()
      val promiseActor = context.actorOf(Props(classOf[PromiseActor], promise))
      val msg = Upgrade(driver, app, keepAlive, maxRunning)
      upgradeManager.tell(msg, promiseActor)

      promise.future.mapTo[Boolean] andThen {
        case Success(_) =>
          log.info(s"Restart of ${app.id} successful")
          eventBus.publish(RestartSuccess(app.id))

        case Failure(e) =>
          log.error(s"Restart of ${app.id} failed", e)
          taskQueue.purge(app)
          eventBus.publish(RestartFailed(app.id))
      }
    }
  }

  def deploy(driver: SchedulerDriver, plan: DeploymentPlan): Future[Unit] = {
    val promise = Promise[Any]()
    val promiseActor = context.actorOf(Props(classOf[PromiseActor], promise))
    val msg = PerformDeployment(driver, plan)
    upgradeManager.tell(msg, promiseActor)

    val res = promise.future.map {
      case Finished  => ()
      case Failed(t) => throw t
    }

    res andThen {
      case Success(_) =>
        log.info(s"Deployment of ${plan.target.id} successful")
        eventBus.publish(DeploymentSuccess(plan.target.id))

      case Failure(e) =>
        log.error(s"Deployment of ${plan.target.id} failed", e)
        distinctApps(plan).foreach(taskQueue.purge)
        eventBus.publish(DeploymentFailed(plan.target.id))
    }
  }

  def distinctIds(plan: DeploymentPlan): Seq[PathId] = distinctApps(plan).map(_.id)

  def distinctApps(plan: DeploymentPlan): Seq[AppDefinition] = {
    val res = for {
      step <- plan.steps
      action <- step.actions
    } yield action.app

    res.distinct
  }
}

object MarathonSchedulerActor {
  sealed trait Command {
    def answer: Event
  }

  case class StartApp(app: AppDefinition) extends Command {
    def answer = AppStarted(app)
  }

  case class StopApp(app: AppDefinition) extends Command {
    def answer = AppStopped(app)
  }

  case class UpdateApp(appId: PathId, update: AppUpdate) extends Command {
    def answer = AppUpdated(appId)
  }

  case class UpgradeApp(app: AppDefinition, keepAlive: Int, maxRunning: Option[Int] = None, force: Boolean = false) extends Command {
    def answer = AppUpgraded(app)
  }

  case object ReconcileTasks extends Command {
    def answer = TasksReconciled
  }

  case class ScaleApp(appId: PathId) extends Command {
    def answer = AppScaled(appId)
  }

  case class LaunchTasks(offers: Seq[OfferID], tasks: Seq[TaskInfo]) extends Command {
    def answer = TasksLaunched(tasks)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = Deployed(plan)
  }

  sealed trait Event
  case class AppStarted(app: AppDefinition) extends Event
  case class AppStopped(app: AppDefinition) extends Event
  case class AppUpdated(appId: PathId) extends Event
  case class AppUpgraded(app: AppDefinition) extends Event
  case class AppScaled(appId: PathId) extends Event
  case object TasksReconciled extends Event
  case class TasksLaunched(tasks: Seq[TaskInfo]) extends Event
  case class Deployed(plan: DeploymentPlan) extends Event

  case class CommandFailed(cmd: Command, reason: Throwable) extends Event

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
    rateLimiters: RateLimiters,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    eventBus: EventStream,
    schedulerActor: ActorRef)(implicit ec: ExecutionContext) {
  import mesosphere.mesos.protos.Implicits._

  private[this] val log = LoggerFactory.getLogger(getClass)

  def startApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    currentAppVersion(app.id).flatMap { appOption =>
      require(appOption.isEmpty, s"Already started app '${app.id}'")

      val persistenceResult = appRepository.store(app).map { _ =>
        log.info(s"Starting app ${app.id}")
        rateLimiters.setPermits(app.id, app.taskRateLimit)
        scale(driver, app)
      }

      persistenceResult.map { _ => healthCheckManager.reconcileWith(app) }
    }
  }

  def stopApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    appRepository.expunge(app.id).map { successes =>
      if (!successes.forall(_ == true)) {
        throw new StorageException("Error expunging " + app.id)
      }

      healthCheckManager.removeAllFor(app.id)

      log.info(s"Stopping app ${app.id}")
      val tasks = taskTracker.get(app.id)

      for (task <- tasks) {
        log.info(s"Killing task ${task.getId}")
        driver.killTask(protos.TaskID(task.getId))
      }
      taskQueue.purge(app)
      taskTracker.shutDown(app.id)
      // TODO after all tasks have been killed we should remove the app from taskTracker
    }
  }

  def updateApp(
    driver: SchedulerDriver,
    id: PathId,
    appUpdate: AppUpdate): Future[AppDefinition] = {
    appRepository.currentVersion(id).flatMap {
      case Some(currentVersion) =>
        val updatedApp = appUpdate(currentVersion)

        healthCheckManager.reconcileWith(updatedApp)

        appRepository.store(updatedApp).map { _ =>
          update(driver, updatedApp, appUpdate)
          updatedApp
        }

      case _ => throw new UnknownAppException(id)
    }
  }

  /**
    * Make sure all apps are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Unit = {
    appRepository.allPathIds().onComplete {
      case Success(iterator) =>
        log.info("Syncing tasks for all apps")
        val buf = mutable.ListBuffer.empty[TaskStatus]
        val appNames = mutable.HashSet.empty[PathId]
        for (appName <- iterator) {
          appNames += appName
          schedulerActor ! ScaleApp(appName)
          val tasks = taskTracker.get(appName)
          for (task <- tasks) {
            val statuses = task.getStatusesList.asScala
            if (statuses.nonEmpty) {
              buf += statuses.last
            }
          }
        }
        for (app <- taskTracker.list.keys) {
          if (!appNames.contains(app)) {
            log.warn(s"App $app exists in TaskTracker, but not App store. The app was likely terminated. Will now expunge.")
            val tasks = taskTracker.get(app)
            for (task <- tasks) {
              log.info(s"Killing task ${task.getId}")
              driver.killTask(protos.TaskID(task.getId))
            }
            taskTracker.expunge(app)
          }
        }
        log.info("Requesting task reconciliation with the Mesos master")
        log.debug(s"Tasks to reconcile: $buf")
        driver.reconcileTasks(buf.asJava)

      case Failure(t) =>
        log.warn("Failed to get task names", t)
    }
  }

  /**
    * Ensures current application parameters (resource requirements, URLs,
    * command, and constraints) are applied consistently across running
    * application instances.
    *
    * @param driver
    * @param updatedApp
    * @param appUpdate
    */
  def update(driver: SchedulerDriver, updatedApp: AppDefinition, appUpdate: AppUpdate): Unit = {
    // TODO: implement app instance restart logic
  }

  /**
    * Make sure the app is running the correct number of instances
    * @param driver
    * @param app
    */
  def scale(driver: SchedulerDriver, app: AppDefinition): Unit = {
    taskTracker.get(app.id).synchronized {
      val currentCount = taskTracker.count(app.id)
      val targetCount = app.instances

      if (targetCount > currentCount) {
        log.info(s"Need to scale ${app.id} from $currentCount up to $targetCount instances")

        val queuedCount = taskQueue.count(app)
        val toQueue = targetCount - (currentCount + queuedCount)

        if (toQueue > 0) {
          log.info(s"Queueing $toQueue new tasks for ${app.id} ($queuedCount queued)")
          for (i <- 0 until toQueue)
            taskQueue.add(app)
        }
        else {
          log.info("Already queued %d tasks for %s. Not scaling.".format(queuedCount, app.id))
        }
      }
      else if (targetCount < currentCount) {
        log.info(s"Scaling ${app.id} from $currentCount down to $targetCount instances")
        taskQueue.purge(app)

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
  }

  def scale(driver: SchedulerDriver, appId: PathId): Future[Unit] = {
    currentAppVersion(appId) map {
      case Some(app) => scale(driver, app)
      case _         => log.warn("App %s does not exist. Not scaling.".format(appId))
    }
  }

  def currentAppVersion(appId: PathId): Future[Option[AppDefinition]] =
    appRepository.currentVersion(appId)
}
