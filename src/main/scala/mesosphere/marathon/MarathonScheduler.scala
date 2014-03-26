package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.{SchedulerDriver, Scheduler}
import java.util.logging.{Level, Logger}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashSet
import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.{MarathonStore, AppRepository}
import scala.util.{Failure, Success}
import scala.concurrent.{Future, ExecutionContext}
import com.google.common.collect.Lists
import javax.inject.{Named, Inject}
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event.{EventModule, MesosStatusUpdateEvent}
import mesosphere.marathon.tasks.{TaskTracker, TaskQueue, TaskIDUtil, MarathonTasks}
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.util.RateLimiters


/**
 * @author Tobi Knaup
 */
class MarathonScheduler @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    @Named("restMapper") mapper: ObjectMapper,
    appRepository: AppRepository,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    rateLimiters: RateLimiters)
  extends Scheduler {

  private val log = Logger.getLogger(getClass.getName)

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  /**
   * Returns a future containing the optional most recent version
   * of the specified app from persistent storage.
   */
  protected[marathon] def currentAppVersion(
    appId: String
  ): Future[Option[AppDefinition]] = appRepository.currentVersion(appId)

  def registered(driver: SchedulerDriver, frameworkId: FrameworkID, master: MasterInfo) {
    log.info("Registered as %s to master '%s'".format(frameworkId.getValue, master.getId))
    frameworkIdUtil.store(frameworkId)
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    log.info("Re-registered to %s".format(master))
  }

  def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    // Check for any tasks which were started but never entered TASK_RUNNING
    // TODO resourceOffers() doesn't feel like the right place to run this
    val toKill = taskTracker.checkStagedTasks
    if (toKill.nonEmpty) {
      log.warning(s"There are ${toKill.size} tasks stuck in staging which will be killed")
      log.info(s"About to kill these tasks: ${toKill}")
      for (task <- toKill) {
        driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
      }
    }
    for (offer <- offers.asScala) {
      try {
        log.fine("Received offer %s".format(offer))

        // TODO launch multiple tasks if the offer is big enough
        val app = taskQueue.poll()

        if (app != null) {
          newTask(app, offer) match {
            case Some((task, ports)) => {
              val taskInfos = Lists.newArrayList(task)
              log.fine("Launching tasks: " + taskInfos)

              val marathonTask = MarathonTasks.makeTask(
                task.getTaskId.getValue, offer.getHostname, ports,
                offer.getAttributesList.asScala.toList)
              taskTracker.starting(app.id, marathonTask)
              driver.launchTasks(Lists.newArrayList(offer.getId), taskInfos)
            }
            case None => {
              log.fine("Offer doesn't match request. Declining.")
              // Add it back into the queue so the we can try again
              taskQueue.add(app)
              driver.declineOffer(offer.getId)
            }
          }
        } else {
          log.fine("Task queue is empty. Declining offer.")
          driver.declineOffer(offer.getId)
        }
      } catch {
        case t: Throwable => {
          log.log(Level.SEVERE, "Caught an exception. Declining offer.", t)
          // Ensure that we always respond
          driver.declineOffer(offer.getId)
        }
      }
    }
  }

  def offerRescinded(driver: SchedulerDriver, offer: OfferID) {
    log.info("Offer %s rescinded".format(offer))
  }

  def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    val appID = TaskIDUtil.appID(status.getTaskId)

    if (status.getState.eq(TaskState.TASK_FAILED)
      || status.getState.eq(TaskState.TASK_FINISHED)
      || status.getState.eq(TaskState.TASK_KILLED)
      || status.getState.eq(TaskState.TASK_LOST)) {

      // Remove from our internal list
      taskTracker.terminated(appID, status).foreach(taskOption => {
        taskOption match {
          case Some(task) => postEvent(status, task)
          case None => log.warning(s"Couldn't post event for ${status.getTaskId}")
        }

        if (rateLimiters.tryAcquire(appID)) {
          scale(driver, appID)
        } else {
          log.warning(s"Rate limit reached for $appID")
        }
      })
    } else if (status.getState.eq(TaskState.TASK_RUNNING)) {
      taskTracker.running(appID, status).onComplete {
        case Success(task) => postEvent(status, task)
        case Failure(t) => {
          log.log(Level.WARNING, s"Couldn't post event for ${status.getTaskId}", t)
          log.warning(s"Killing task ${status.getTaskId}")
          driver.killTask(TaskID.newBuilder.setValue(status.getTaskId.getValue).build)
        }
      }
    } else if (status.getState.eq(TaskState.TASK_STAGING) && !taskTracker.contains(appID)) {
      log.warning(s"Received status update for unknown app ${appID}")
      log.warning(s"Killing task ${status.getTaskId}")
      driver.killTask(TaskID.newBuilder.setValue(status.getTaskId.getValue).build)
    } else {
      taskTracker.statusUpdate(appID, status).onComplete {
        case Success(t) =>
          t match {
            case None =>
              log.warning(s"Killing task ${status.getTaskId}")
              driver.killTask(TaskID.newBuilder.setValue(status.getTaskId.getValue).build)
            case _ =>
          }
        case _ =>
      }
    }
  }

  def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, message: Array[Byte]) {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
  }

  def disconnected(driver: SchedulerDriver) {
    log.warning("Disconnected")
    suicide()
  }

  def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info("Lost slave %s".format(slave))
  }

  def executorLost(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, p4: Int) {
    log.info("Lost executor %s %s %s ".format(executor, slave, p4))
  }

  def error(driver: SchedulerDriver, message: String) {
    log.warning("Error: %s".format(message))
    suicide()
  }

  // TODO move stuff below out of the scheduler

  def startApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    currentAppVersion(app.id).flatMap { appOption =>
      require(appOption.isEmpty, s"Already started app '${app.id}'")

      appRepository.store(app).map { _ =>
        log.info(s"Starting app ${app.id}")
        rateLimiters.setPermits(app.id, app.taskRateLimit)
        scale(driver, app)
      }
    }
  }

  def stopApp(driver: SchedulerDriver, app: AppDefinition): Future[_] = {
    appRepository.expunge(app.id).map { successes =>
      if (!successes.forall(_ == true)) {
        throw new StorageException("Error expunging " + app.id )
      }

      log.info(s"Stopping app ${app.id}")
      val tasks = taskTracker.get(app.id)

      for (task <- tasks) {
        log.info(s"Killing task ${task.getId}")
        driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
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
      case Some(currentVersion) => {
        val updatedApp = appUpdate(currentVersion)
        
        appRepository.store(updatedApp).map { _ =>
          update(driver, updatedApp, appUpdate)
          updatedApp
        }
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
  def reconcileTasks(driver: SchedulerDriver) {
    appRepository.appIds().onComplete {
      case Success(iterator) => {
        log.info("Syncing tasks for all apps")
        val buf = new ListBuffer[TaskStatus]
        val appNames = new HashSet[String]
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
            log.warning(s"App ${app} exists in TaskTracker, but not App store. The app was likely terminated. Will now expunge.")
            val tasks = taskTracker.get(app)
            for (task <- tasks) {
              log.info(s"Killing task ${task.getId}")
              driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
            }
            taskTracker.expunge(app)
          }
        }
        log.info("Requesting task reconciliation with the Mesos master")
        log.finest(s"Tasks to reconcile: $buf")
        driver.reconcileTasks(buf.asJava)
      }
      case Failure(t) => {
        log.log(Level.WARNING, "Failed to get task names", t)
      }
    }
  }

  private def newTask(app: AppDefinition,
                      offer: Offer): Option[(TaskInfo, Seq[Int])] = {
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
  private def update(driver: SchedulerDriver, updatedApp: AppDefinition, appUpdate: AppUpdate) {
    // TODO: implement app instance restart logic
  }

  /**
   * Make sure the app is running the correct number of instances
   * @param driver
   * @param app
   */
  def scale(driver: SchedulerDriver, app: AppDefinition) {
    taskTracker.get(app.id).synchronized {
      val currentCount = taskTracker.count(app.id)
      val targetCount = app.instances

      if (targetCount > currentCount) {
        log.info("Need to scale %s from %d up to %d instances".format(app.id, currentCount, targetCount))

        val queuedCount = taskQueue.count(app)

        if ((currentCount + queuedCount) < targetCount) {
          for (i <- (currentCount + queuedCount) until targetCount) {
            log.info(s"Queueing new task for ${app.id} ($queuedCount queued)")
            taskQueue.add(app)
          }
        } else {
          log.info("Already queued %d tasks for %s. Not scaling.".format(queuedCount, app.id))
        }
      }
      else if (targetCount < currentCount) {
        log.info("Scaling %s from %d down to %d instances".format(app.id, currentCount, targetCount))

        val toKill = taskTracker.take(app.id, currentCount - targetCount)
        for (task <- toKill) {
          log.info("Killing task " + task.getId)
          driver.killTask(TaskID.newBuilder.setValue(task.getId).build)
        }
      }
      else {
        log.info("Already running %d instances. Not scaling.".format(app.instances))
      }
    }
  }

  private def scale(driver: SchedulerDriver, appName: String) {
    currentAppVersion(appName).onSuccess {
      case Some(app) => scale(driver, app)
      case _ => log.warning("App %s does not exist. Not scaling.".format(appName))
    }
  }

  private def suicide() {
    log.severe("Committing suicide")
    sys.exit(9)
  }

  private def postEvent(status: TaskStatus, task: MarathonTask) {
    if (eventBus.isEmpty) {
      return
    }

    log.info("Sending event notification.")
    val event = new MesosStatusUpdateEvent(
      status.getTaskId.getValue,
      status.getState.getNumber,
      TaskIDUtil.appID(status.getTaskId),
      task.getHost,
      task.getPortsList.asScala)
    eventBus.get.post(event)
  }
}
