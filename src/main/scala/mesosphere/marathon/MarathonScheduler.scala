package mesosphere.marathon

import javax.inject.{ Inject, Named }

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.EventStream
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event._
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId, Timestamp }
import mesosphere.marathon.tasks._
import mesosphere.marathon.tasks.TaskQueue.QueuedTask
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.mesos.{ TaskBuilder, protos }
import org.apache.log4j.Logger
import org.apache.mesos.Protos._
import org.apache.mesos.{ Scheduler, SchedulerDriver }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future }
import scala.util.{ Failure, Success }

trait SchedulerCallbacks {
  def disconnected(): Unit
}

object MarathonScheduler {
  private class MarathonSchedulerCallbacksImpl(
    serviceOption: Option[MarathonSchedulerService])
      extends SchedulerCallbacks {

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
    @Named(EventModule.busName) eventBus: EventStream,
    @Named("restMapper") mapper: ObjectMapper,
    @Named("schedulerActor") schedulerActor: ActorRef,
    appRepo: AppRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    taskIdUtil: TaskIdUtil,
    system: ActorSystem,
    config: MarathonConf) extends Scheduler {

  private[this] val log = Logger.getLogger(getClass.getName)

  import mesosphere.mesos.protos.Implicits._
  import mesosphere.util.ThreadPoolContext.context

  implicit val zkTimeout = config.zkFutureTimeout

  override def registered(
    driver: SchedulerDriver,
    frameworkId: FrameworkID,
    master: MasterInfo): Unit = {
    log.info(s"Registered as ${frameworkId.getValue} to master '${master.getId}'")
    frameworkIdUtil.store(frameworkId)
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    log.info("Re-registered to %s".format(master))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    // Check for any tasks which were started but never entered TASK_RUNNING
    // TODO resourceOffers() doesn't feel like the right place to run this
    val toKill = taskTracker.checkStagedTasks

    if (toKill.nonEmpty) {
      log.warn(s"There are ${toKill.size} tasks stuck in staging which will be killed")
      log.info(s"About to kill these tasks: $toKill")
      for (task <- toKill)
        driver.killTask(protos.TaskID(task.getId))
    }

    // remove queued tasks with stale (non-current) app definition versions
    val appVersions: Map[PathId, Timestamp] =
      Await.result(appRepo.currentAppVersions(), config.zkTimeoutDuration)

    taskQueue.retain {
      case QueuedTask(app, _) =>
        appVersions.get(app.id) contains app.version
    }

    for (offer <- offers.asScala) {
      try {
        log.debug("Received offer %s".format(offer))

        val queuedTasks: Seq[QueuedTask] = taskQueue.removeAll()

        val withTaskInfos: Seq[(QueuedTask, (TaskInfo, Seq[Long]))] =
          queuedTasks.view.flatMap { qt => newTask(qt.app, offer).map(qt -> _) }.to[Seq]

        val launchedTask = withTaskInfos.dropWhile {
          case (qt, (taskInfo, ports)) =>
            val timeLeft = qt.delay.timeLeft
            if (timeLeft.toNanos <= 0) {
              false
            }
            else {
              log.info(s"Delaying task ${taskInfo.getTaskId.getValue} due to backoff. Time left: $timeLeft.")
              true
            }
        }.headOption

        launchedTask.foreach {
          case (qt, (taskInfo, ports)) =>
            val taskInfos = Seq(taskInfo)
            log.debug("Launching tasks: " + taskInfos)

            val marathonTask = MarathonTasks.makeTask(
              taskInfo.getTaskId.getValue, offer.getHostname, ports,
              offer.getAttributesList.asScala, qt.app.version)

            taskTracker.created(qt.app.id, marathonTask)
            driver.launchTasks(Seq(offer.getId).asJava, taskInfos.asJava)
        }

        // put unscheduled tasks back in the queue
        val launchedTaskSeq: Seq[QueuedTask] = launchedTask.map(_._1).to[Seq]
        taskQueue.addAll(queuedTasks diff launchedTaskSeq)

        if (launchedTask.isEmpty) {
          log.debug("Offer doesn't match request. Declining.")
          driver.declineOffer(offer.getId)
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

  override def offerRescinded(driver: SchedulerDriver, offer: OfferID): Unit = {
    log.info("Offer %s rescinded".format(offer))
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {

    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    val appId = taskIdUtil.appId(status.getTaskId)

    // forward health changes to the health check manager
    for (marathonTask <- taskTracker.fetchTask(appId, status.getTaskId.getValue))
      healthCheckManager.update(status, Timestamp(marathonTask.getVersion))

    import org.apache.mesos.Protos.TaskState._

    val killedForFailingHealthChecks =
      status.getState == TASK_KILLED && status.hasHealthy && !status.getHealthy

    if (status.getState == TASK_FAILED || killedForFailingHealthChecks)
      appRepo.currentVersion(appId).foreach {
        _.foreach(taskQueue.rateLimiter.addDelay)
      }
    status.getState match {
      case TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        // Remove from our internal list
        taskTracker.terminated(appId, status).foreach(taskOption => {
          taskOption match {
            case Some(task) => postEvent(status, task)
            case None       => log.warn(s"Couldn't post event for ${status.getTaskId}")
          }

          schedulerActor ! ScaleApp(appId)
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
        taskTracker.statusUpdate(appId, status).onSuccess {
          case None =>
            log.warn(s"Killing task ${status.getTaskId}")
            driver.killTask(status.getTaskId)
        }
    }
  }

  override def frameworkMessage(
    driver: SchedulerDriver,
    executor: ExecutorID,
    slave: SlaveID,
    message: Array[Byte]): Unit = {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
    eventBus.publish(MesosFrameworkMessageEvent(executor.getValue, slave.getValue, message))
  }

  def unhealthyTaskKilled(appId: PathId, taskId: String): Unit = {
    log.warn(s"Task [$taskId] for app [$appId] was killed for failing too many health checks")
    appRepo.currentVersion(appId).foreach {
      _.foreach { app => taskQueue.rateLimiter.addDelay(app) }
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
        if (status.hasMessage) status.getMessage else "",
        taskIdUtil.appId(status.getTaskId),
        task.getHost,
        task.getPortsList.asScala,
        task.getVersion
      )
    )
  }

  private def newTask(
    app: AppDefinition,
    offer: Offer): Option[(TaskInfo, Seq[Long])] = {
    // TODO this should return a MarathonTask
    new TaskBuilder(app, taskIdUtil.newTaskId, taskTracker, config, mapper).buildIfMatches(offer)
  }
}
