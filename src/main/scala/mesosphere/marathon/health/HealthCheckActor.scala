package mesosphere.marathon.health

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonScheduler, MarathonSchedulerDriverHolder }
import mesosphere.mesos.protos.TaskID

class HealthCheckActor(
    appId: PathId,
    appVersion: String,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    marathonScheduler: MarathonScheduler,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.health.HealthCheckActor.{ GetTaskHealth, _ }
  import mesosphere.marathon.health.HealthCheckWorker.HealthCheckJob
  import mesosphere.mesos.protos.Implicits._

  var nextScheduledCheck: Option[Cancellable] = None
  var taskHealth = Map[String, Health]()

  val workerProps = Props[HealthCheckWorkerActor]

  override def preStart(): Unit = {
    log.info(
      "Starting health check actor for app [{}] version [{}] and healthCheck [{}]",
      appId,
      appVersion,
      healthCheck
    )
    scheduleNextHealthCheck()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    log.info(
      "Restarting health check actor for app [{}] version [{}] and healthCheck [{}]",
      appId,
      appVersion,
      healthCheck
    )

  override def postStop(): Unit = {
    nextScheduledCheck.forall { _.cancel() }
    log.info(
      "Stopped health check actor for app [{}] version [{}] and healthCheck [{}]",
      appId,
      appVersion,
      healthCheck
    )
  }

  def purgeStatusOfDoneTasks(): Unit = {
    log.debug(
      "Purging health status of done tasks for app [{}] version [{}] and healthCheck [{}]",
      appId,
      appVersion,
      healthCheck
    )
    val activeTaskIds = taskTracker.getTasks(appId).map(_.getId).toSet
    // The Map built with filterKeys wraps the original map and contains a reference to activeTaskIds.
    // Therefore we materialize it into a new map.
    taskHealth = taskHealth.filterKeys(activeTaskIds).iterator.toMap
  }

  def scheduleNextHealthCheck(): Unit =
    if (healthCheck.protocol != Protocol.COMMAND) {
      log.debug(
        "Scheduling next health check for app [{}] version [{}] and healthCheck [{}]",
        appId,
        appVersion,
        healthCheck
      )
      nextScheduledCheck = Some(
        context.system.scheduler.scheduleOnce(healthCheck.interval) {
          self ! Tick
        }
      )
    }

  def dispatchJobs(): Unit = {
    log.debug("Dispatching health check jobs to workers")
    taskTracker.getTasks(appId).foreach { task =>
      if (task.getVersion == appVersion && task.hasStartedAt) {
        log.debug("Dispatching health check job for task [{}]", task.getId)
        val worker: ActorRef = context.actorOf(workerProps)
        worker ! HealthCheckJob(task, healthCheck)
      }
    }
  }

  def checkConsecutiveFailures(task: MarathonTask,
                               health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      log.info(f"Detected unhealthy task ${task.getId} of app [$appId] version [$appVersion] on host ${task.getHost}")

      // kill the task
      marathonSchedulerDriverHolder.driver.foreach { driver =>
        log.info(s"Send kill request for task ${task.getId} on host ${task.getHost} to driver")
        driver.killTask(TaskID(task.getId))
      }
    }
  }

  def ignoreFailures(task: MarathonTask,
                     health: Health): Boolean = {
    // Ignore failures during the grace period, until the task becomes green
    // for the first time.  Also ignore failures while the task is staging.
    !task.hasStartedAt ||
      health.firstSuccess.isEmpty &&
      task.getStartedAt + healthCheck.gracePeriod.toMillis > System.currentTimeMillis()
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def receive: Receive = {
    case GetTaskHealth(taskId) => sender() ! taskHealth.getOrElse(taskId, Health(taskId))

    case GetAppHealth =>
      sender() ! AppHealth(taskHealth.values.toSeq)

    case Tick =>
      purgeStatusOfDoneTasks()
      dispatchJobs()
      scheduleNextHealthCheck()

    case result: HealthResult if result.version == appVersion =>
      log.info("Received health result for app [{}] version [{}]: [{}]", appId, appVersion, result)
      val taskId = result.taskId
      val health = taskHealth.getOrElse(taskId, Health(taskId))

      val newHealth = result match {
        case Healthy(_, _, _) =>
          health.update(result)
        case Unhealthy(_, _, _, _) =>
          taskTracker.getTask(appId, taskId) match {
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

      taskHealth += (taskId -> newHealth)

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
  // self-sent every healthCheck.intervalSeconds
  case object Tick
  case class GetTaskHealth(taskId: String)
  case object GetAppHealth

  case class AppHealth(health: Seq[Health])
}
