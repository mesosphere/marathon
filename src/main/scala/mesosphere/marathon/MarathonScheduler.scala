package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.{ SchedulerDriver, Scheduler }
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashSet
import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.AppRepository
import scala.concurrent.{Future, ExecutionContext}
import com.google.common.collect.Lists
import javax.inject.{ Named, Inject }
import akka.event.EventStream
import mesosphere.marathon.event._
import mesosphere.marathon.tasks._
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.protos
import mesosphere.util.{LockManager, ThreadPoolContext, RateLimiters}
import mesosphere.marathon.health.HealthCheckManager
import scala.util.{ Success, Failure }
import org.apache.log4j.Logger
import akka.actor.{Props, ActorSystem}
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.event.MesosFrameworkMessageEvent
import mesosphere.marathon.event.RestartSuccess
import mesosphere.marathon.upgrade.AppUpgradeManager
import mesosphere.marathon.upgrade.AppUpgradeManager.Upgrade
import akka.pattern.ask
import scala.concurrent.duration._
import akka.util.Timeout
trait SchedulerCallbacks {
  def disconnected(): Unit
}

object MarathonScheduler {
  private class MarathonSchedulerCallbacksImpl(serviceOption: Option[MarathonSchedulerService]) extends SchedulerCallbacks {
    override def disconnected(): Unit = {
      // Abdicate leadership when we become disconnected from the Mesos master.
      serviceOption.foreach(_.abdicateLeadership())
    }
  }

  val callbacks: SchedulerCallbacks = new MarathonSchedulerCallbacksImpl(Some(Main.injector.getInstance(classOf[MarathonSchedulerService])))
}

class MarathonScheduler @Inject() (
  @Named(EventModule.busName) eventBus: EventStream,
    @Named("restMapper") mapper: ObjectMapper,
    appRepository: AppRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    rateLimiters: RateLimiters,
    system: ActorSystem,
    config: MarathonConf) extends Scheduler {

  private [this] val log = Logger.getLogger(getClass.getName)
  private [this] val upgradeManager = system.actorOf(
    Props(classOf[AppUpgradeManager], taskTracker, taskQueue, eventBus))
  val appLocks = LockManager()

  import ThreadPoolContext.context
  import mesosphere.mesos.protos.Implicits._

  implicit val zkTimeout = config.zkFutureTimeout

  /**
    * Returns a future containing the optional most recent version
    * of the specified app from persistent storage.
    */
  protected[marathon] def currentAppVersion(
    appId: String): Future[Option[AppDefinition]] = appRepository.currentVersion(appId)

  override def registered(driver: SchedulerDriver, frameworkId: FrameworkID, master: MasterInfo) {
    log.info("Registered as %s to master '%s'".format(frameworkId.getValue, master.getId))
    frameworkIdUtil.store(frameworkId)
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    log.info("Re-registered to %s".format(master))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    // Check for any tasks which were started but never entered TASK_RUNNING
    // TODO resourceOffers() doesn't feel like the right place to run this
    val toKill = taskTracker.checkStagedTasks
    if (toKill.nonEmpty) {
      log.warn(s"There are ${toKill.size} tasks stuck in staging which will be killed")
      log.info(s"About to kill these tasks: $toKill")
      for (task <- toKill)
        driver.killTask(protos.TaskID(task.getId))
    }
    for (offer <- offers.asScala) {
      try {
        log.debug("Received offer %s".format(offer))

        val apps = taskQueue.removeAll()
        var i = 0
        var found = false

        while (i < apps.size && !found) {
          // TODO launch multiple tasks if the offer is big enough
          val app = apps(i)

          newTask(app, offer) match {
            case Some((task, ports)) =>
              val taskInfos = Lists.newArrayList(task)
              log.debug("Launching tasks: " + taskInfos)

              val marathonTask = MarathonTasks.makeTask(
                task.getTaskId.getValue, offer.getHostname, ports,
                offer.getAttributesList.asScala.toList, app.version)
              taskTracker.starting(app.id, marathonTask)
              driver.launchTasks(Lists.newArrayList(offer.getId), taskInfos)
              found = true

            case None =>
              taskQueue.add(app)
          }

          i += 1
        }

        if (!found) {
          log.debug("Offer doesn't match request. Declining.")
          // Add it back into the queue so the we can try again
          driver.declineOffer(offer.getId)
        }
        else {
          taskQueue.addAll(apps.drop(i))
        }
      }
      catch {
        case t: Throwable =>
          log.error("Caught an exception. Declining offer.", t)
          // Ensure that we always respond
          driver.declineOffer(offer.getId)
      }
    }
  }

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID) {
    log.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    import TaskState._

    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    val appID = TaskIDUtil.appID(status.getTaskId)

    status.getState match {
      case TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        // Remove from our internal list
        taskTracker.terminated(appID, status) foreach { taskOption =>
          taskOption match {
            case Some(task) => postEvent(status, task)
            case None => log.warn(s"Couldn't post event for ${status.getTaskId}")
          }

          if (rateLimiters.tryAcquire(appID)) {
            scale(driver, appID)
          } else {
        else {
            log.warn(s"Rate limit reached for $appID")
          }
        }

      case TASK_RUNNING =>
        taskTracker.running(appID, status).onComplete {
          case Success(task) => postEvent(status, task)
          case Failure(t) =>
            log.warn(s"Couldn't post event for ${status.getTaskId}", t)
            log.warn(s"Killing task ${status.getTaskId}")
            driver.killTask(status.getTaskId)
        }

      case TASK_STAGING if !taskTracker.contains(appID) =>
        log.warn(s"Received status update for unknown app $appID")
        log.warn(s"Killing task ${status.getTaskId}")
        driver.killTask(status.getTaskId)

      case _ =>
        taskTracker.statusUpdate(appID, status) onSuccess {
          case None =>
            log.warn(s"Killing task ${status.getTaskId}")
            driver.killTask(status.getTaskId)
          case _ =>
        }
    }
  }

  override def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, message: Array[Byte]) {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
    eventBus.publish(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message))
  }

  override def disconnected(driver: SchedulerDriver) {
    log.warn("Disconnected")

    // Disconnection from the Mesos master has occurred. Thus, call the scheduler callbacks.
    MarathonScheduler.callbacks.disconnected()
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info("Lost slave %s".format(slave))
  }

  override def executorLost(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, p4: Int) {
    log.info("Lost executor %s %s %s ".format(executor, slave, p4))
  }

  override def error(driver: SchedulerDriver, message: String) {
    log.warn("Error: %s".format(message))
    suicide()
  }

  // TODO move stuff below out of the scheduler

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
    id: String,
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
          scale(driver, appName)
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

  private def newTask(app: AppDefinition,
                      offer: Offer): Option[(TaskInfo, Seq[Long])] = {
    // TODO this should return a MarathonTask
    new TaskBuilder(app, taskTracker.newTaskId, taskTracker, mapper).buildIfMatches(offer)
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
  private def update(driver: SchedulerDriver, updatedApp: AppDefinition, appUpdate: AppUpdate): Unit = {
    // TODO: implement app instance restart logic
  }

  /**
    * Make sure the app is running the correct number of instances
    * @param driver
    * @param app
    */
  def scale(driver: SchedulerDriver, app: AppDefinition): Unit = {
    val lock = appLocks.get(app.id)
    if (lock.tryAcquire()) {
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
      } finally lock.release()
    }
  }

  private def scale(driver: SchedulerDriver, appName: String): Unit = {
    currentAppVersion(appName).onSuccess {
      case Some(app) => scale(driver, app)
      case _         => log.warn("App %s does not exist. Not scaling.".format(appName))
    }
  }

  private def suicide(): Unit = {
    log.fatal("Committing suicide")

    // Asynchronously call sys.exit() to avoid deadlock due to the JVM shutdown hooks
    Future {
      sys.exit(9)
    } onFailure {
      case t: Throwable => log.fatal("Exception while committing suicide", t)
    }
  }

  private def postEvent(status: TaskStatus, task: MarathonTask): Unit = {
    log.info("Sending event notification.")
    eventBus.publish(
      MesosStatusUpdateEvent(
        status.getSlaveId.getValue,
        status.getTaskId.getValue,
        status.getState.name,
        TaskIDUtil.appID(status.getTaskId),
        task.getHost,
        task.getPortsList.asScala,
        task.getVersion
      )
    )
  }
}
