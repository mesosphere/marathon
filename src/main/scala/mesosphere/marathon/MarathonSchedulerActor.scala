package mesosphere.marathon

import java.util.concurrent.Semaphore

import akka.actor._
import akka.event.EventStream
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.mesos.Protos.TaskInfo
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.event.{ DeploymentFailed, DeploymentSuccess }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{ DeploymentRepository, AppDefinition, AppRepository, PathId }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.upgrade.DeploymentManager._
import mesosphere.marathon.upgrade.{ DeploymentManager, DeploymentPlan, TaskKillActor }
import mesosphere.mesos.protos.Offer
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.{ TaskBuilder, protos }
import mesosphere.util.{ LockManager, PromiseActor }

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future, Promise, blocking }
import scala.util.{ Failure, Success }

class MarathonSchedulerActor(
    mapper: ObjectMapper,
    appRepository: AppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    taskIdUtil: TaskIdUtil,
    storage: StorageProvider,
    eventBus: EventStream,
    config: MarathonConf) extends Actor with ActorLogging with Stash {
  import context.dispatcher

  import mesosphere.marathon.MarathonSchedulerActor._

  val appLocks = LockManager[PathId]()
  var scheduler: SchedulerActions = _

  var deploymentManager: ActorRef = _

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
      Props(classOf[DeploymentManager], appRepository, taskTracker, taskQueue, scheduler, storage, eventBus), "UpgradeManager")

    deploymentRepository.all() onComplete {
      case Success(deployments) => self ! RecoveredDeployments(deployments)
      case Failure(_)           => self ! RecoveredDeployments(Nil)
    }
  }

  def receive = recovering

  def recovering: Receive = {
    case RecoveredDeployments(deployments) =>
      deployments.foreach { plan =>
        log.info(s"Recovering deployment: $plan")
        deploy(Actor.noSender, Deploy(plan, force = false), plan, blocking = false)
      }
      unstashAll()
      context.become(ready)

    case _ => stash()
  }

  def ready: Receive = {
    case ReconcileTasks =>
      scheduler.reconcileTasks(driver)
      sender ! ReconcileTasks.answer

    case cmd @ ScaleApp(appId) =>
      val origSender = sender()
      performAsyncWithLockFor(appId, origSender, cmd, blocking = false) {
        val res = scheduler.scale(driver, appId)

        if (origSender != Actor.noSender)
          res.sendAnswer(origSender, cmd)
        else
          res
      }

    case cmd @ Deploy(plan, false) =>
      deploy(sender(), cmd, plan, blocking = false)

    case cmd @ Deploy(plan, true) =>
      deploymentManager ! CancelConflictingDeployments(
        plan,
        new DeploymentCanceledException("The upgrade has been cancelled")
      )
      deploy(sender(), cmd, plan, blocking = true)

    case cmd @ KillTasks(appId, taskIds, scale) =>
      val origSender = sender()
      performAsyncWithLockFor(appId, origSender, cmd, blocking = true) {
        val promise = Promise[Unit]()
        val tasksToKill = taskIds.flatMap(taskTracker.fetchTask(appId, _)).toSet
        context.actorOf(Props(classOf[TaskKillActor], driver, appId, taskTracker, eventBus, tasksToKill, promise))
        val res = if (scale) {
          for {
            _ <- promise.future
            currentApp <- appRepository.currentVersion(appId)
            _ <- currentApp.map(app => appRepository.store(app.copy(instances = app.instances - tasksToKill.size))).getOrElse(Future.successful(()))
          } yield ()
        }
        else promise.future

        res.sendAnswer(origSender, cmd)
      }

    case ConflictingDeploymentsCanceled(id) =>
      log.info(s"Conflicting deployments for deployment $id have been canceled")

    case RetrieveRunningDeployments =>
      deploymentManager forward RetrieveRunningDeployments
  }

  /**
    * Tries to acquire the lock for all given appIds.
    * If it succeeds it executes the given function,
    * otherwise a [CommandFailed] message is sent to
    * the original sender.
    */
  def performAsyncWithLockFor[U](appIds: Set[PathId], origSender: ActorRef, cmd: Command, isBlocking: Boolean)(f: => Future[U]): Future[_] = {

    def performWithLock(lockFn: Set[Semaphore] => Set[Semaphore]): Future[_] = {
      val locks = appIds.map(appLocks.get) //needed locks for all application
      Future(blocking(lockFn(locks))).flatMap { acquired => //acquired locks, fetched in a future
        if (acquired.size == locks.size) {
          log.debug(s"Acquired locks for $appIds, performing cmd: $cmd")
          f andThen {
            case _ =>
              log.debug(s"Releasing locks for $appIds")
              acquired.foreach(_.release())
          }
        }
        else lockNotAvailable(acquired)
      }
    }

    def lockNotAvailable(acquiredLocks: Set[Semaphore]): Future[_] = Future {
      log.debug(s"Failed to acquire some of the locks for $appIds to perform cmd: $cmd")
      acquiredLocks.foreach(_.release())

      import scala.concurrent.duration._
      import akka.pattern.ask
      import akka.util.Timeout

      deploymentManager.ask(RetrieveRunningDeployments)(Timeout(2.seconds))
        .mapTo[RunningDeployments]
        .foreach {
          case RunningDeployments(plans) =>
            val relatedDeploymentIds: Seq[String] = plans.collect {
              case (p, _) if p.affectedApplicationIds.intersect(appIds).nonEmpty => p.id
            }
            origSender ! CommandFailed(cmd, AppLockedException(relatedDeploymentIds))
        }
    }

    def acquireBlocking(locks: Set[Semaphore]) = locks.map{ s => s.acquire(); s }
    def acquireIfPossible(locks: Set[Semaphore]) = locks.takeWhile(_.tryAcquire())
    performWithLock(if (isBlocking) acquireBlocking else acquireIfPossible)
  }

  /**
    * Tries to acquire the lock for the given appId.
    * If it succeeds it executes the given function,
    * otherwise a [CommandFailed] message is sent to
    * the original sender.
    */
  def performAsyncWithLockFor[U](appId: PathId, origSender: ActorRef, cmd: Command, blocking: Boolean)(f: => Future[U]): Future[_] =
    performAsyncWithLockFor(Set(appId), origSender, cmd, blocking)(f)

  // there has to be a better way...
  def driver: SchedulerDriver = MarathonSchedulerDriver.driver.get

  def deploy(origSender: ActorRef, cmd: Command, plan: DeploymentPlan, blocking: Boolean): Unit = {
    val ids = plan.affectedApplicationIds

    performAsyncWithLockFor(ids, origSender, cmd, isBlocking = blocking) {
      ids.foreach(taskQueue.rateLimiter.resetDelay)

      val res = deploy(driver, plan)
      if (origSender != Actor.noSender) origSender ! cmd.answer
      res
    }
  }

  def deploy(driver: SchedulerDriver, plan: DeploymentPlan): Future[Unit] = {
    deploymentRepository.store(plan).flatMap { _ =>
      val promise = Promise[Any]()
      val promiseActor = context.actorOf(Props(classOf[PromiseActor], promise))
      val msg = PerformDeployment(driver, plan)
      deploymentManager.tell(msg, promiseActor)

      val res = promise.future.map(_ => ())

      res andThen {
        case Success(_) =>
          log.info(s"Deployment of ${plan.target.id} successful")
          eventBus.publish(DeploymentSuccess(plan.id))
          deploymentRepository.expunge(plan.id)

        case Failure(e) =>
          log.error(s"Deployment of ${plan.target.id} failed", e)
          plan.affectedApplicationIds.foreach(appId => taskQueue.purge(appId))
          eventBus.publish(DeploymentFailed(plan.id))
          if (e.isInstanceOf[DeploymentCanceledException])
            deploymentRepository.expunge(plan.id)
      }
    }
  }
}

object MarathonSchedulerActor {
  case class RecoveredDeployments(deployments: Seq[DeploymentPlan])

  sealed trait Command {
    def answer: Event
  }

  case object ReconcileTasks extends Command {
    def answer = TasksReconciled
  }

  case class ScaleApp(appId: PathId) extends Command {
    def answer = AppScaled(appId)
  }

  case class Deploy(plan: DeploymentPlan, force: Boolean = false) extends Command {
    def answer: Event = DeploymentStarted(plan)
  }

  case class KillTasks(appId: PathId, taskIds: Set[String], scale: Boolean) extends Command {
    def answer = TasksKilled(appId, taskIds)
  }

  case object RetrieveRunningDeployments

  sealed trait Event
  case class AppScaled(appId: PathId) extends Event
  case object TasksReconciled extends Event
  case class DeploymentStarted(plan: DeploymentPlan) extends Event
  case class TasksKilled(appId: PathId, taskIds: Set[String]) extends Event

  case class RunningDeployments(plans: Seq[(DeploymentPlan, DeploymentStepInfo)])

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
    mapper: ObjectMapper,
    appRepository: AppRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskIdUtil: TaskIdUtil,
    taskQueue: TaskQueue,
    eventBus: EventStream,
    schedulerActor: ActorRef,
    config: MarathonConf)(implicit ec: ExecutionContext) {
  import mesosphere.mesos.protos.Implicits._

  private[this] val log = LoggerFactory.getLogger(getClass)

  // TODO move stuff below out of the scheduler

  def startApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    currentAppVersion(app.id).flatMap { appOption =>
      require(appOption.isEmpty, s"Already started app '${app.id}'")

      val persistenceResult = appRepository.store(app).map { _ =>
        log.info(s"Starting app ${app.id}")
        scale(driver, app)
      }

      persistenceResult.map { _ => healthCheckManager.reconcileWith(app) }
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
      taskQueue.rateLimiter.resetDelay(app.id)
      // TODO after all tasks have been killed we should remove the app from taskTracker
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
    appRepository.allPathIds().map(_.toSet).onComplete {
      case Success(appIds) =>
        log.info("Syncing tasks for all apps")

        for (appId <- appIds) schedulerActor ! ScaleApp(appId)

        val knownTaskStatuses = appIds.flatMap { appId =>
          taskTracker.get(appId).collect {
            case task if task.hasStatus => task.getStatus
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
        driver.reconcileTasks(knownTaskStatuses.asJava)

      case Failure(t) =>
        log.warn("Failed to get task names", t)
    }
  }

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

        healthCheckManager.reconcileWith(updatedApp)
        taskQueue.purge(id)
        taskQueue.rateLimiter.resetDelay(id)

        appRepository.store(updatedApp).map { _ =>
          update(driver, updatedApp, appUpdate)
          updatedApp
        }

      case _ => throw new UnknownAppException(id)
    }
  }

  def currentAppVersion(appId: PathId): Future[Option[AppDefinition]] =
    appRepository.currentVersion(appId)
}
