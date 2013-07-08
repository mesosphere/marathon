package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.{SchedulerDriver, Scheduler}
import java.util.logging.{Level, Logger}
import scala.collection.JavaConverters._
import java.util.concurrent.LinkedBlockingQueue
import mesosphere.mesos.MesosUtils
import mesosphere.marathon.api.v1.ServiceDefinition
import mesosphere.marathon.state.MarathonStore
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import com.google.common.collect.Lists


/**
 * @author Tobi Knaup
 */
class MarathonScheduler(store: MarathonStore[ServiceDefinition]) extends Scheduler {

  val log = Logger.getLogger(getClass.getName)

  val taskTracker = new TaskTracker

  val taskQueue = new LinkedBlockingQueue[TaskInfo.Builder]()

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  def registered(driver: SchedulerDriver, frameworkId: FrameworkID, master: MasterInfo) {
    log.info("Registered as %s to master '%s'".format(frameworkId.getValue, master.getId))
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    log.info("Re-registered to %s".format(master))
  }

  def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    for (offer <- offers.asScala) {
      log.finer("Received offer %s".format(offer.getId.getValue))

      val taskBuilder = taskQueue.poll()

      if (taskBuilder == null) {
        log.fine("Task queue is empty. Declining offer.")
        driver.declineOffer(offer.getId)
      }
      else if (MesosUtils.offerMatches(offer, taskBuilder)) {
        val taskInfos = Lists.newArrayList(
          taskBuilder.setSlaveId(offer.getSlaveId).build()
        )
        log.fine("Launching tasks: " + taskInfos)
        driver.launchTasks(offer.getId, taskInfos)
      }
      else {
        log.fine("Offer doesn't match request. Declining.")
        // Add it back into the queue so the we can try again
        taskQueue.add(taskBuilder)
        driver.declineOffer(offer.getId)
      }
    }
  }

  def offerRescinded(driver: SchedulerDriver, offer: OfferID) {
    log.info("Offer %s rescinded".format(offer))
  }

  def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    val appName = TaskIDUtil.appName(status.getTaskId)

    if (status.getState.eq(TaskState.TASK_FAILED)
      || status.getState.eq(TaskState.TASK_FINISHED)
      || status.getState.eq(TaskState.TASK_KILLED)
      || status.getState.eq(TaskState.TASK_LOST)) {

      // Remove from our internal list
      taskTracker.remove(appName, status.getTaskId)
      scale(driver, appName)
    } else if (status.getState.eq(TaskState.TASK_RUNNING)) {
      taskTracker.add(appName, status.getTaskId)
    }
  }

  def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, message: Array[Byte]) {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
  }

  def disconnected(driver: SchedulerDriver) {
    log.warning("Disconnected")
    driver.stop
  }

  def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info("Lost slave %s".format(slave))
  }

  def executorLost(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, p4: Int) {
    log.info("Lost executor %s %s %s ".format(executor, slave, p4))
  }

  def error(driver: SchedulerDriver, message: String) {
    log.warning("Error: %s".format(message))
    driver.stop
  }

  // TODO move stuff below out of the scheduler

  def startService(driver: SchedulerDriver, service: ServiceDefinition) {
    store.fetch(service.id).onComplete {
      case Success(option) => if (option.isEmpty) {
        store.store(service.id, service)
        log.info("Starting service " + service.id)
        scale(driver, service)
      } else {
        log.warning("Already started service " + service.id)
      }
      case Failure(t) =>
        log.log(Level.WARNING, "Failed to start service %s".format(service.id), t)
    }
  }

  def stopService(driver: SchedulerDriver, service: ServiceDefinition) {
    store.expunge(service.id).onComplete {
      case Success(_) =>
        log.info("Stopping service " + service.id)
        val taskIds = taskTracker.get(service.id)

        for (taskId <- taskIds) {
          log.info("Killing task " + taskId.getValue)
          driver.killTask(taskId)
        }

        // TODO after all tasks have been killed we should remove the app from taskTracker
      case Failure(t) =>
        log.warning("Error stopping service %s: %s".format(service.id, t.getMessage))
    }
  }

  def scaleService(driver: SchedulerDriver, service: ServiceDefinition) {
    store.fetch(service.id).onComplete {
      case Success(option) => if (option.isDefined) {
        val storedService = option.get

        scale(driver, storedService)
      } else {
        val msg = "Service unknown: " + service.id
        log.warning(msg)
      }
      case Failure(t) =>
        log.warning("Error scaling service %s: %s".format(service.id, t.getMessage))
    }
  }

  /**
   * Make sure all apps are running the configured amount of tasks.
   *
   * Should be called some time after the framework re-registers,
   * to give Mesos enough time to deliver task updates.
   * @param driver scheduler driver
   */
  def balanceTasks(driver: SchedulerDriver) {
    store.names().onComplete {
      case Success(iterator) => {
        log.info("Syncing tasks for all apps")
        for (appName <- iterator) {
          scale(driver, appName)
        }
      }
      case Failure(t) => {
        log.log(Level.WARNING, "Failed to get task names", t)
      }
    }
  }

  private

  def newTask(service: ServiceDefinition) = {
    val taskId = taskTracker.newTaskId(service.id)

    TaskInfo.newBuilder
      .setName(taskId.getValue)
      .setTaskId(taskId)
      .setCommand(MesosUtils.commandInfo(service))
      .addAllResources(MesosUtils.resources(service))
  }

  /**
   * Make sure the service is running the correct number of instances
   * @param driver
   * @param service
   */
  def scale(driver: SchedulerDriver, service: ServiceDefinition) {
    val currentSize = taskTracker.count(service.id)
    val targetSize = service.instances

    if (targetSize > currentSize) {
      log.info("Scaling %s from %d up to %d instances".format(service.id, currentSize, targetSize))

      for (i <- currentSize until targetSize) {
        val task = newTask(service)
        log.info("Queueing task " + task.getTaskId.getValue)
        taskQueue.add(task)
      }
    }
    else if (targetSize < currentSize) {
      log.info("Scaling %s from %d down to %d instances".format(service.id, currentSize, targetSize))

      val kill = taskTracker.drop(service.id, targetSize)
      for (taskId <- kill) {
        log.info("Killing task " + taskId.getValue)
        driver.killTask(taskId)
      }
    }
    else {
      log.info("Already running %d instances. Not scaling.".format(service.instances))
    }
  }

  def scale(driver: SchedulerDriver, appName: String) {
    store.fetch(appName).onSuccess {
      case Some(service) => scale(driver, service)
      case None => log.warning("App %s does not exist. Not scaling.".format(appName))
    }
  }

}
