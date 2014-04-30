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
import mesosphere.marathon.event.{FrameworkMessageEvent, EventModule, MesosStatusUpdateEvent}
import mesosphere.marathon.tasks.{TaskTracker, TaskQueue, TaskIDUtil, MarathonTasks}
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.protos
import mesosphere.util.RateLimiters
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import scala.util.{Try, Success, Failure}


sealed trait SysResources {
  def cpu: Double
  def mem: Double
}
trait AvailableResources  extends SysResources {
  def offer: Offer
  def -[T <: NeededResources[_]](needs: T): Option[this.type]
  def ports: Seq[(Int, Int)]
}

trait NeededResources[T] extends SysResources {
  def source: T
  def ports: Seq[Int]
}

trait OfferMatch[T] {
  def toReserve: NeededResources[T]
  def available: AvailableResources
  def remaining: Option[OfferMatch[T]]
}

object AppDefinitionNeeds {
  def apply(source: AppDefinition): AppDefinitionNeeds = {
    val cpu = source.cpus
    val mem = source.mem
    val ports = source.ports
    AppDefinitionNeeds(source, cpu, mem, ports.map(a => a:Int))
  }
}
case class AppDefinitionNeeds(source: AppDefinition, cpu: Double, mem: Double, ports: Seq[Int]) extends NeededResources[AppDefinition]

object AvailableOffer {
  def apply(offer: Offer): AvailableOffer = {
    val cpuAvail = offer.getResourcesList.asScala.find(_.getName == mesosphere.mesos.protos.Resource.CPUS)
    val memAvail = offer.getResourcesList.asScala.find(_.getName == mesosphere.mesos.protos.Resource.MEM)
    val ports = offer.getResourcesList.asScala.find(_.getName == mesosphere.mesos.protos.Resource.PORTS)
    val ranges = 
      ports.fold(Seq.empty[(Int, Int)]) { r =>
          r.getRanges.getRangeList.asScala.map(kv => (kv.getBegin.toInt, kv.getEnd.toInt))
      }
    
    AvailableOffer(offer, cpuAvail.fold(0.0)(_.getScalar.getValue), memAvail.fold(0.0)(_.getScalar.getValue), ranges)
  }
}
case class AvailableOffer(offer: Offer, cpu: Double, mem: Double, ports: Seq[(Int, Int)]) extends AvailableResources {
  def -[T <: NeededResources[_]](needed: T): Option[this.type] = {
    val cpuLeft = cpu - needed.cpu
    val memLeft = mem - needed.mem
    val portsLeft = needed.ports.foldLeft(Vector.newBuilder[Seq[(Int, Int)]]) { (acc, p) =>
      ports.find(minMax => minMax._1 <= p && minMax._2 >= p).fold(acc)({
        case (min, max) =>
          // calculate new available port ranges based on the ports needed by the app definition
          val newRange =
            if (min == p) Vector((min + 1, max))
            else if (max == p) Vector((min, max - 1))
            else Vector((min, p - 1), (p + 1, max))
          acc += newRange
      })
    }.result()
    // When these sizes are different we weren't able to reserve all the necessary ports
    if (cpuLeft < 0 || memLeft < 0 || portsLeft.size != needed.ports.size) {
      None
    } else {
      val remainingPorts = portsLeft.flatten.toVector // put lipstick on the pig
      Some(copy(cpu = cpuLeft, mem = memLeft, ports = remainingPorts).asInstanceOf[this.type])
    }

  }
}

/*
  An interface to work out which resource to pick
  It allows for filtering
 */
final class NeededResourcesPlan(available: Seq[AppDefinition]) {

  private[this] def byConstraintSize(left: AppDefinition, right: AppDefinition): Boolean =
    left.constraints.size > right.constraints.size

  private[this] def byCpuCountForSize(left: AppDefinition, right: AppDefinition): Boolean =
    left.constraints.size == right.constraints.size && left.cpus > right.cpus

  private[this] def byMemorySizeForCpu(left: AppDefinition, right: AppDefinition): Boolean =
    left.constraints.size == right.constraints.size && left.cpus == right.cpus && left.mem > right.mem

  private[this] def sortedWithFreePorts = { // no idea how big these guys get, sticking with a def for now
    available sortWith {
      case (left, right) =>
        byConstraintSize(left, right) || byCpuCountForSize(left, right) || byMemorySizeForCpu(left, right)
    }
  }
  
  def createPlan(offer: Offer): Seq[AppDefinition] = {
    val (builder, _) =
      sortedWithFreePorts.foldLeft((Vector.newBuilder[AppDefinition], AvailableOffer(offer))) {
        case (acc @ (b, o), i) =>
          val remaining = o - AppDefinitionNeeds(i)
          remaining.fold(acc)(r => (b += i, r))
      }
    builder.result()
  }
}

/**
 * @author Tobi Knaup
 */
class MarathonScheduler @Inject()(
  @Named(EventModule.busName) eventBus: Option[EventBus],
  @Named("restMapper") mapper: ObjectMapper,
  appRepository: AppRepository,
  healthCheckManager: HealthCheckManager,
  taskTracker: TaskTracker,
  taskQueue: TaskQueue,
  frameworkIdUtil: FrameworkIdUtil,
  rateLimiters: RateLimiters
) extends Scheduler {

  private[this] val log = Logger.getLogger(getClass.getName)

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global
  import mesosphere.mesos.protos.Implicits._

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
        driver.killTask(protos.TaskID(task.getId))
      }
    }
    for (offer <- offers.asScala) {
      try {
        log.fine("Received offer %s".format(offer))

        val apps = taskQueue.removeAll().toVector // empty queue
        val planner = new NeededResourcesPlan(apps)
        val planned = planner.createPlan(offer)
        
        var found = planned.nonEmpty
        
        planned foreach { app =>
          newTask(app, offer) match {
            case Some((task, ports)) =>
              val taskInfos = Lists.newArrayList(task)
              log.fine("Launching tasks: " + taskInfos)

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

        if (!found) {
          log.fine("Offer doesn't match request. Declining.")
          // Add it back into the queue so the we can try again
          driver.declineOffer(offer.getId)
        } else {
          taskQueue.addAll(apps.filterNot(planned.contains))
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
          driver.killTask(status.getTaskId)
        }
      }
    } else if (status.getState.eq(TaskState.TASK_STAGING) && !taskTracker.contains(appID)) {
      log.warning(s"Received status update for unknown app ${appID}")
      log.warning(s"Killing task ${status.getTaskId}")
      driver.killTask(status.getTaskId)
    } else {
      taskTracker.statusUpdate(appID, status).onComplete {
        case Success(t) =>
          t match {
            case None =>
              log.warning(s"Killing task ${status.getTaskId}")
              driver.killTask(status.getTaskId)
            case _ =>
          }
        case _ =>
      }
    }
  }

  def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, message: Array[Byte]) {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
    eventBus.foreach(_.post(FrameworkMessageEvent(executor.getValue, slave.getValue, message)))
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
      case Some(currentVersion) => {
        val updatedApp = appUpdate(currentVersion)

        healthCheckManager.reconcileWith(updatedApp)

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
              driver.killTask(protos.TaskID(task.getId))
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
          driver.killTask(protos.TaskID(task.getId))
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
    eventBus.foreach {
      bus => {
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
}
