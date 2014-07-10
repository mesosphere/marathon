package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.{ SchedulerDriver, Scheduler }
import scala.collection.JavaConverters._
import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.AppRepository
import scala.concurrent.Future
import com.google.common.collect.Lists
import javax.inject.{ Named, Inject }
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event._
import mesosphere.marathon.tasks.{ TaskTracker, TaskQueue, TaskIDUtil, MarathonTasks }
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.protos
import mesosphere.util.ThreadPoolContext
import mesosphere.marathon.health.HealthCheckManager
import scala.util.{ Try, Success, Failure }
import org.apache.log4j.Logger

trait SchedulerCallbacks {
  def disconnected(): Unit
}

object MarathonScheduler {

  private class MarathonSchedulerCallbacksImpl(
      serviceOption: Option[MarathonSchedulerService]) extends SchedulerCallbacks {
    override def disconnected(): Unit = {
      // Abdicate leadership when we become disconnected from the Mesos master.
      serviceOption.foreach(_.abdicateLeadership())
    }
  }

  val callbacks: SchedulerCallbacks = new MarathonSchedulerCallbacksImpl(
    Some(Main.injector.getInstance(classOf[MarathonSchedulerService]))
  )
}

class MarathonScheduler @Inject() (
    @Named(EventModule.busName) eventBus: Option[EventBus],
    @Named("restMapper") mapper: ObjectMapper,
    appRepository: AppRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    config: MarathonConf) extends Scheduler {

  private val log = Logger.getLogger(getClass.getName)

  import ThreadPoolContext.context
  import mesosphere.mesos.protos.Implicits._

  implicit val zkTimeout = config.zkFutureTimeout

  /**
    * Returns a future containing the optional most recent version
    * of the specified app from persistent storage.
    */
  protected[marathon] def currentAppVersion(
    appId: String): Future[Option[AppDefinition]] = appRepository.currentVersion(appId)

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo) {
    log.info(s"Registered as ${frameworkId.getValue} to master '${master.getId}'")
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

    import TaskQueue.QueuedTask

    for (offer <- offers.asScala) {
      try {
        log.debug(s"Received offer $offer")

        val queuedTasks: Seq[QueuedTask] = taskQueue.removeAll()
        var i = 0
        var found = false

        while (i < queuedTasks.size && !found) {
          // TODO launch multiple tasks if the offer is big enough
          val QueuedTask(app, delay) = queuedTasks(i)

          if (delay.isOverdue) {
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
          }

          i += 1
        }

        if (!found) {
          log.debug("Offer doesn't match request. Declining.")
          // Add it back into the queue so the we can try again
          taskQueue.addAll(queuedTasks)
          driver.declineOffer(offer.getId)
        }
        else {
          val (prefix, suffix) = (queuedTasks take i - 1, queuedTasks drop i)
          taskQueue.addAll(prefix ++ suffix)
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
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    // conditionally forward health changes to the health check manager
    if (config.executorHealthChecks()) healthCheckManager.update(status)

    val appId = TaskIDUtil.appID(status.getTaskId)

    import TaskState.{ TASK_STAGING, TASK_RUNNING, TASK_FAILED, TASK_FINISHED, TASK_KILLED, TASK_LOST }

    if (status.getState == TASK_FAILED || status.getState == TASK_LOST)
      currentAppVersion(appId).foreach {
        _.foreach(taskQueue.rateLimiter.addDelay(_))
      }

    status.getState match {
      case TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        // Remove from our internal list
        taskTracker.terminated(appId, status).foreach(taskOption => {
          taskOption match {
            case Some(task) => postEvent(status, task)
            case None       => log.warn(s"Couldn't post event for ${status.getTaskId}")
          }

          scale(driver, appId)
        })

      case TASK_RUNNING =>
        taskQueue.rateLimiter.resetDelay(appId)
        taskTracker.running(appId, status).onComplete {
          case Success(task) => postEvent(status, task)
          case Failure(t) =>
            log.warn(s"Couldn't post event for ${status.getTaskId}", t)
            log.warn(s"Killing task ${status.getTaskId}")
            driver.killTask(status.getTaskId)
        }

      case TASK_STAGING if !taskTracker.contains(appId) =>
        log.warn(s"Received status update for unknown app $appId")
        log.warn(s"Killing task ${status.getTaskId}")
        driver.killTask(status.getTaskId)

      case _ =>
        taskTracker.statusUpdate(appId, status).onComplete {
          case Success(t) =>
            t match {
              case None =>
                log.warn(s"Killing task ${status.getTaskId}")
                driver.killTask(status.getTaskId)
              case _ =>
            }
          case _ =>
        }
    }
  }

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]) {
    log.info(s"Received framework message $executor $slave $message")
    eventBus foreach {
      _.post(
        MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message)
      )
    }
  }

  override def disconnected(driver: SchedulerDriver) {
    log.warn("Disconnected")

    // Disconnection from the Mesos master has occurred.
    // Thus, call the scheduler callbacks.
    MarathonScheduler.callbacks.disconnected()
  }

  override def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info(s"Lost slave $slave")
  }

  override def executorLost(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    p4: Int) {
    log.info(s"Lost executor $executor slave $p4")
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
      taskTracker.shutDown(app.id)
      // TODO after all tasks have been killed we should remove the app from taskTracker
    }
  }

  def updateApp(
    driver: SchedulerDriver,
    id: String,
    appUpdate: AppUpdate): Future[AppDefinition] = {
    currentAppVersion(id).flatMap {
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

  /**
    * Make sure all apps are running the configured amount of tasks.
    *
    * Should be called some time after the framework re-registers,
    * to give Mesos enough time to deliver task updates.
    * @param driver scheduler driver
    */
  def reconcileTasks(driver: SchedulerDriver): Unit = {
    appRepository.appIds().map(_.toSet).onComplete {
      case Success(appIds) =>
        log.info("Syncing tasks for all apps")

        for (appId <- appIds) scale(driver, appId)

        val knownTaskStatuses = appIds.flatMap { appId =>
          taskTracker.get(appId).flatMap(_.getStatusesList.asScala.lastOption)
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
          taskTracker.expunge(unknownAppId)
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
    val builder = new TaskBuilder(app, taskTracker.newTaskId, taskTracker, mapper)

    builder.buildIfMatches(offer) map {
      case (task, ports) =>
        val taskBuilder = task.toBuilder

        if (config.executorHealthChecks()) {
          import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol

          val host = offer.getHostname

          // Mesos supports at most one health check, and only COMMAND checks
          // are currently implemented.
          val mesosHealthCheck: Option[org.apache.mesos.Protos.HealthCheck] =
            app.healthChecks.collectFirst {
              case healthCheck if healthCheck.protocol == Protocol.COMMAND =>
                Try(healthCheck.toMesos(host, ports.map(_.toInt))) match {
                  case Success(mesosHealthCheck) => Some(mesosHealthCheck)
                  case Failure(cause) =>
                    log.warn(
                      s"An error occurred with health check [$healthCheck]\n" +
                        s"Error: [${cause.getMessage}]")
                    None
                }
            }.flatten

          mesosHealthCheck foreach taskBuilder.setHealthCheck

          if (mesosHealthCheck.size < app.healthChecks.size) {
            val numUnusedChecks = app.healthChecks.size - mesosHealthCheck.size
            log.warn(
              "Mesos supports one command health check per task.\n" +
                s"Task [${task.getTaskId.getValue}] will run without " +
                s"$numUnusedChecks of its defined health checks."
            )
          }
        }

        taskBuilder.build -> ports
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
  private def update(
    driver: SchedulerDriver,
    updatedApp: AppDefinition,
    appUpdate: AppUpdate): Unit = {
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

  private def scale(driver: SchedulerDriver, appName: String): Unit = {
    currentAppVersion(appName).onSuccess {
      case Some(app) => scale(driver, app)
      case _         => log.warn(s"App $appName does not exist. Not scaling.")
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
    eventBus.foreach { bus =>
      log.info("Sending event notification.")
      bus.post(
        MesosStatusUpdateEvent(
          status.getSlaveId.getValue,
          status.getTaskId.getValue,
          status.getState.name,
          TaskIDUtil.appID(status.getTaskId),
          task.getHost,
          task.getPortsList.asScala
        )
      )
    }
  }
}
