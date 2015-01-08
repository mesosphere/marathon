package mesosphere.marathon.health

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerDriver
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event._
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.protos.TaskID

class HealthCheckActor(
    appId: PathId,
    appVersion: String,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.health.HealthCheckActor.GetTaskHealth
  import mesosphere.marathon.health.HealthCheckWorker.HealthCheckJob
  import mesosphere.mesos.protos.Implicits._

  protected[this] var nextScheduledCheck: Option[Cancellable] = None

  protected[this] var taskHealth = Map[String, Option[Health]]()

  override def preStart(): Unit = {
    log.info(
      "Starting health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
    scheduleNextHealthCheck()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    log.info(
      "Restarting health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )

  override def postStop(): Unit = {
    nextScheduledCheck.forall { _.cancel() }
    log.info(
      "Stopped health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
  }

  // self-sent every healthCheck.intervalSeconds
  protected[this] case object Tick

  protected[this] def purgeStatusOfDoneTasks(): Unit = {
    log.debug(
      "Purging health status of done tasks for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
    val activeTaskIds = taskTracker.get(appId).map(_.getId)
    taskHealth = taskHealth.filterKeys(activeTaskIds)
  }

  protected[this] def scheduleNextHealthCheck(): Unit = {
    val interval = if (healthCheck.protocol == Protocol.COMMAND) {
      // Mesos reports positive health checks only once. If this instance
      // has taken over leadership after a COMMAND check task was started,
      // it will never see the positive health check result. Hence, we
      // fake it, assuming that a COMMAND health check is positive if no
      // negative result is received.
      log.debug(
        "Scheduling next health check for app [{}] and healthCheck [{}]",
        appId,
        healthCheck
      )
      healthCheck.interval + healthCheck.timeout // wait longer than interval
    } else {
      log.debug(
        "Scheduling next health check for app [{}] and healthCheck [{}]",
        appId,
        healthCheck
      )
      healthCheck.interval
    }

    nextScheduledCheck = Some(
      context.system.scheduler.scheduleOnce(interval) {
        self ! Tick
      }
    )
  }

  protected[this] def fakePositiveCommandChecks(): Unit = {
    // fake positive health check result if task were seen during the previous tick
    // already and now a second time, still None as health.
    val activeTaskIds = taskTracker.get(appId).filter(_.getVersion == appVersion).map(_.getId)
    val fakePositiveCandidates = taskHealth.filter(_._2 == None).keys.filter(activeTaskIds)
    fakePositiveCandidates foreach { taskId => {
      log.info(s"Fake COMMAND health check result for app ${appId} task ${taskId}")
      self ! Healthy(taskId, appVersion)
    }}

    // write None health for new tasks for next tick
    activeTaskIds foreach { taskId =>
      if (!taskHealth.contains(taskId))
        taskHealth += taskId -> None
    }
  }

  protected[this] def dispatchJobs(): Unit = {
    log.debug("Dispatching health check jobs to workers")
    taskTracker.get(appId).foreach { task =>
      if (task.getVersion() == appVersion) {
        log.debug("Dispatching health check job for task [{}]", task.getId)
        val worker: ActorRef = context.actorOf(Props[HealthCheckWorkerActor])
        worker ! HealthCheckJob(task, healthCheck)
      }
    }
  }

  protected[this] def checkConsecutiveFailures(task: MarathonTask,
                                               health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      log.info(f"Killing task ${task.getId} on host ${task.getHost}")

      // kill the task
      MarathonSchedulerDriver.driver.foreach { driver =>
        driver.killTask(TaskID(task.getId))
      }

      // increase the task launch delay for this questionably healthy app
      MarathonSchedulerDriver.scheduler.foreach { scheduler =>
        scheduler.unhealthyTaskKilled(appId, task.getId)
      }
    }
  }

  protected[this] def ignoreFailures(task: MarathonTask,
                                     health: Health): Boolean = {
    // Ignore failures during the grace period, until the task becomes green
    // for the first time.  Also ignore failures while the task is staging.
    !task.hasStartedAt ||
      health.firstSuccess.isEmpty &&
      task.getStartedAt + healthCheck.gracePeriod.toMillis > System.currentTimeMillis()
  }

  def receive: Receive = {
    case GetTaskHealth(taskId) => sender() ! taskHealth.get(taskId).flatten

    case Tick => {
      if (healthCheck.protocol == Protocol.COMMAND) {
        fakePositiveCommandChecks()
        purgeStatusOfDoneTasks()
      } else {
        purgeStatusOfDoneTasks()
        dispatchJobs()
      }
      scheduleNextHealthCheck()
    }

    case result: HealthResult if result.version == appVersion =>
      log.info("Received health result: [{}]", result)
      val taskId = result.taskId
      val health: Health = taskHealth.getOrElse(taskId, None).getOrElse(Health(taskId))

      val newHealth = result match {
        case Healthy(_, _, _) =>
          health.update(result)
        case Unhealthy(_, _, _, _) =>
          taskTracker.get(appId).find(_.getId == taskId) match {
            case Some(task) =>
              if (ignoreFailures(task, health)) {
                // Don't update health
                health
              }
              else {
                eventBus.publish(FailedHealthCheck(appId, taskId, healthCheck))
                checkConsecutiveFailures(task, health)
                health.update(result)
              }
            case None =>
              log.error(s"Couldn't find task $taskId")
              health.update(result)
          }
      }

      taskHealth += (taskId -> Some(newHealth))

      if (health.alive != newHealth.alive) {
        eventBus.publish(
          HealthStatusChanged(
            appId = appId,
            taskId = taskId,
            version = result.version,
            alive = newHealth.alive)
        )
      }

    case result: HealthResult =>
      log.warning(s"Ignoring health result [$result] due to version mismatch.")

  }
}

object HealthCheckActor {
  case class GetTaskHealth(taskId: String)
}
