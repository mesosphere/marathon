package mesosphere.marathon

import java.util
import org.apache.mesos.Protos._
import org.apache.mesos.{SchedulerDriver, Scheduler}
import java.util.logging.Logger
import scala.collection._
import scala.collection.JavaConversions._
import java.util.concurrent.LinkedBlockingQueue
import mesosphere.mesos.MesosUtils
import mesosphere.marathon.api.v1.ServiceDefinition
import mesosphere.marathon.state.MarathonStore
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext


/**
 * @author Tobi Knaup
 */
class MarathonScheduler(store: MarathonStore) extends Scheduler {

  val log = Logger.getLogger(getClass.getName)

  // TODO better way to keep this state?
  val tasks = mutable.Map.empty[String, mutable.ArrayBuffer[TaskID]]

  val taskQueue = new LinkedBlockingQueue[TaskInfo.Builder]()

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  def registered(driver: SchedulerDriver, frameworkId: FrameworkID, master: MasterInfo) {
    log.info("Registered as %s to master '%s'".format(frameworkId.getValue, master.getId))
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    log.info("Re-registered to %s".format(master))
  }

  def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]) {
    for (offer <- offers) {
      log.info("Received offer %s".format(offer.getId.getValue))

      val taskBuilder = taskQueue.poll()

      if (taskBuilder == null) {
        log.info("Task queue is empty. Declining offer.")
        driver.declineOffer(offer.getId)
      }
      else if (MesosUtils.offerMatches(offer.getResourcesList.toList, taskBuilder.getResourcesList.toList)) {
        val taskInfos = List(taskBuilder.setSlaveId(offer.getSlaveId).build())
        log.info("Launching tasks: " + taskInfos)
        driver.launchTasks(offer.getId, taskInfos)
      }
      else {
        log.info("Offer doesn't match request. Declining.")
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

    if (status.getState.eq(TaskState.TASK_FAILED)
      || status.getState.eq(TaskState.TASK_FINISHED)
      || status.getState.eq(TaskState.TASK_KILLED)
      || status.getState.eq(TaskState.TASK_LOST)) {

      // Remove from our internal list
      // Horrible
      val taskIdString = status.getTaskId.getValue
      for ((appName, taskIds) <- tasks) {
        if (taskIdString.startsWith(appName)) {
          val idx = taskIds.indexOf(status.getTaskId)
          if (idx >= 0)
            taskIds.remove(idx)
          scale(driver, appName)
        }
      }
    } else if (status.getState.eq(TaskState.TASK_RUNNING)) {
      // TODO implement, for reconnect
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
        tasks(service.id) = new mutable.ArrayBuffer[TaskID]
        log.info("Starting service " + service.id)
        scale(driver, service)
      } else {
        log.warning("Already started service " + service.id)
      }
      case Failure(t) =>
        log.warning("Error starting service %s: %s".format(service.id, t.getMessage))
    }
  }

  def stopService(driver: SchedulerDriver, service: ServiceDefinition) {
    store.expunge(service.id).onComplete {
      case Success(_) =>
        log.info("Stopping service " + service.id)
        val taskIds = tasks.remove(service.id).get

        for (taskId <- taskIds) {
          log.info("Killing task " + taskId.getValue)
          driver.killTask(taskId)
        }
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

  private

  def newTask(service: ServiceDefinition) = {
    val serviceTasks = tasks(service.id)
    val taskId = TaskID.newBuilder()
      .setValue("%s-%d-%d".format(service.id, serviceTasks.size, System.currentTimeMillis()))
      .build

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
    val serviceTasks = tasks(service.id)
    val currentSize = serviceTasks.size
    val targetSize = service.instances

    if (targetSize > currentSize) {
      log.info("Scaling %s from %d up to %d instances".format(service.id, currentSize, targetSize))

      while (serviceTasks.size < targetSize) {
        val task = newTask(service)
        log.info("Queueing task " + task.getTaskId.getValue)
        serviceTasks += task.getTaskId
        taskQueue.add(task)
      }
    }
    else if (targetSize < currentSize) {
      log.info("Scaling %s from %d down to %d instances".format(service.id, currentSize, targetSize))

      val kill = serviceTasks.drop(targetSize)
      tasks(service.id) = serviceTasks.take(targetSize)
      for (taskId <- kill) {
        log.info("Killing task " + taskId.getValue)
        driver.killTask(taskId)
      }
    }
    else {
      log.info("Already running %d instances. Not scaling.".format(service.instances))
    }
  }

  def scale(driver: SchedulerDriver, serviceName: String) {
    store.fetch(serviceName).onSuccess {
      case service => scale(driver, service.get)
    }
  }

}
