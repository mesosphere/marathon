package mesosphere.marathon

import akka.actor._
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.{ExecutionContext, Future}
import mesosphere.mesos.protos
import mesosphere.marathon.api.v2.AppUpdate
import akka.util.Timeout
import scala.collection.mutable.{HashSet, ListBuffer}
import org.apache.mesos.Protos.{TaskInfo, TaskStatus}
import mesosphere.marathon.state.AppRepository
import mesosphere.util.{LockManager, RateLimiters}
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.{TaskQueue, TaskTracker}
import mesosphere.marathon.upgrade.AppUpgradeManager
import akka.event.EventStream
import mesosphere.mesos.util.FrameworkIdUtil
import scala.util.Failure
import mesosphere.marathon.event.{RestartFailed, RestartSuccess}
import mesosphere.marathon.upgrade.AppUpgradeManager.{CancelUpgrade, Upgrade}
import scala.util.Success
import scala.collection.JavaConverters._
import akka.pattern.ask
import scala.concurrent.duration._
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import org.apache.mesos.Protos.OfferID

class MarathonSchedulerActor(
  val appRepository: AppRepository,
  val healthCheckManager: HealthCheckManager,
  val taskTracker: TaskTracker,
  val taskQueue: TaskQueue,
  val frameworkIdUtil: FrameworkIdUtil,
  val rateLimiters: RateLimiters,
  val eventBus: EventStream
) extends Actor with ActorLogging with SchedulerActions {
  import context.dispatcher
  import MarathonSchedulerActor._

  val appLocks = LockManager()

  var upgradeManager: ActorRef = _

  override def preStart(): Unit = {
    upgradeManager = context.actorOf(
      Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus), "UpgradeManager")
  }

  def receive = {
    case cmd @ StartApp(app) =>
      val origSender = sender
      locking(app.id, origSender, cmd) {
        startApp(driver, app).sendAnswer(origSender, cmd)
      }

    case cmd @ StopApp(app) =>
      val origSender = sender
      locking(app.id, origSender, cmd) {
        stopApp(driver, app).sendAnswer(origSender, cmd)
      }

    case cmd @ UpdateApp(appId, update) =>
      val origSender = sender
      locking(appId, origSender, cmd) {
        updateApp(driver, appId, update).sendAnswer(origSender, cmd)
      }

    case cmd @ UpgradeApp(app, keepAlive) =>
      val origSender = sender
      locking(app.id, origSender, cmd) {
        upgradeApp(driver, app, keepAlive).sendAnswer(origSender, cmd)
      }

    case cmd @ RollbackApp(app, keepAlive) =>
      val origSender = sender
      upgradeManager ! CancelUpgrade(app.id)
      locking(app.id, origSender, cmd, blocking = true) {
        upgradeApp(driver, app, keepAlive).sendAnswer(origSender, cmd)
      }

    case cmd @ ReconcileTasks =>
      reconcileTasks(driver)
      sender ! cmd.answer

    case cmd @ ScaleApp(appId) =>
      val origSender = sender
      locking(appId, origSender, cmd) {
        scale(driver, appId).sendAnswer(origSender, cmd)
      }

    case cmd @ LaunchTasks(offers, tasks) =>
      driver.launchTasks(offers.asJava, tasks.asJava)
      sender ! cmd.answer
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
  def locking[U](appId: String, origSender: ActorRef, cmd: Command, blocking: Boolean = false)(f: => Future[U]): Unit = {
    val lock = appLocks.get(appId)
    if (blocking) {
      lock.acquire()
      f andThen { case _ =>
        lock.release()
      }
    }
    else if (lock.tryAcquire()) {
      f andThen { case _ =>
        lock.release()
      }
    } else {
      origSender ! CommandFailed(cmd, new AppLockedException)
    }
  }

  // there has to be a better way...
  def driver: SchedulerDriver = MarathonSchedulerDriver.driver.get
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

  case class UpdateApp(appId: String, update: AppUpdate) extends Command {
    def answer = AppUpdated(appId)
  }

  case class UpgradeApp(app: AppDefinition, keepAlive: Int) extends Command {
    def answer = AppUpgraded(app)
  }

  case object ReconcileTasks extends Command {
    def answer = TasksReconciled
  }

  case class ScaleApp(appId: String) extends Command {
    def answer = AppScaled(appId)
  }

  case class LaunchTasks(offers: Seq[OfferID], tasks: Seq[TaskInfo]) extends Command {
    def answer = TasksLaunched(tasks)
  }

  case class RollbackApp(app: AppDefinition, keepAlive: Int) extends Command {
    def answer = AppRolledBack(app)
  }

  sealed trait Event
  case class AppStarted(app: AppDefinition) extends Event
  case class AppStopped(app: AppDefinition) extends Event
  case class AppUpdated(appId: String) extends Event
  case class AppUpgraded(app: AppDefinition) extends Event
  case class AppScaled(appId: String) extends Event
  case object TasksReconciled extends Event
  case class TasksLaunched(tasks: Seq[TaskInfo]) extends Event
  case class AppRolledBack(app: AppDefinition) extends Event

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

trait SchedulerActions { this: Actor with ActorLogging =>
  import context.dispatcher
  import mesosphere.mesos.protos.Implicits._

  def appRepository: AppRepository
  def rateLimiters: RateLimiters
  def healthCheckManager: HealthCheckManager
  def taskTracker: TaskTracker
  def taskQueue: TaskQueue
  def upgradeManager: ActorRef
  def eventBus: EventStream

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
        throw new StorageException("Error expunging " + app.id )
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
    id: String,
    appUpdate: AppUpdate
  ): Future[AppDefinition] = {
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

  def upgradeApp(
    driver: SchedulerDriver,
    app: AppDefinition,
    keepAlive: Int
  ): Future[Boolean] = {
    appRepository.store(app) flatMap {
      case Some(appDef) =>
        // TODO: this should be configurable
        implicit val timeout = Timeout(12.hours)
        val res = (upgradeManager ? Upgrade(driver, app, keepAlive)).mapTo[Boolean]

        res andThen {
          case Success(_) =>
            log.info(s"Restart of ${app.id} successful")
            eventBus.publish(RestartSuccess(app.id))

          case Failure(e) =>
            log.error(s"Restart of ${app.id} failed", e)
            taskQueue.purge(app)
            eventBus.publish(RestartFailed(app.id))
        }

      case None => Future.failed(new StorageException("App could not be stored"))
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
    appRepository.allIds().onComplete {
      case Success(iterator) =>
        log.info("Syncing tasks for all apps")
        val buf = new ListBuffer[TaskStatus]
        val appNames = HashSet.empty[String]
        for (appName <- iterator) {
          appNames += appName
          self ! ScaleApp(appName)
          val tasks = taskTracker.get(appName)
          for (task <- tasks) {
            val statuses = task.getStatusesList.asScala.toList
            if (statuses.nonEmpty) {
              buf += statuses.last
            }
          }
        }
        for (app <- taskTracker.list.keys) {
          if (!appNames.contains(app)) {
            log.warning(s"App $app exists in TaskTracker, but not App store. The app was likely terminated. Will now expunge.")
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
        log.warning("Failed to get task names", t)
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
    try {
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
          } else {
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
  }

  def scale(driver: SchedulerDriver, appName: String): Future[Unit] = {
    currentAppVersion(appName) map {
      case Some(app) => scale(driver, app)
      case _ => log.warning("App %s does not exist. Not scaling.".format(appName))
    }
  }

  def currentAppVersion(appId: String): Future[Option[AppDefinition]] =
      appRepository.currentVersion(appId)
}
