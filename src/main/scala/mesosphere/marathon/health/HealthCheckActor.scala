package mesosphere.marathon.health

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.event._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.core.task.state.MarathonTaskStatus.Unreachable

private[health] class HealthCheckActor(
    app: AppDefinition,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.health.HealthCheckActor.{ GetTaskHealth, _ }
  import mesosphere.marathon.health.HealthCheckWorker.HealthCheckJob

  var nextScheduledCheck: Option[Cancellable] = None
  var taskHealth = Map[Task.Id, Health]()

  val workerProps = Props[HealthCheckWorkerActor]

  override def preStart(): Unit = {
    log.info(
      "Starting health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
    scheduleNextHealthCheck()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    log.info(
      "Restarting health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )

  override def postStop(): Unit = {
    nextScheduledCheck.forall { _.cancel() }
    log.info(
      "Stopped health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
  }

  def purgeStatusOfDoneTasks(): Unit = {
    log.debug(
      "Purging health status of done tasks for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
    val activeTaskIds = taskTracker.appTasksLaunchedSync(app.id).map(_.taskId).toSet
    // The Map built with filterKeys wraps the original map and contains a reference to activeTaskIds.
    // Therefore we materialize it into a new map.
    taskHealth = taskHealth.filterKeys(activeTaskIds).iterator.toMap
  }

  def scheduleNextHealthCheck(): Unit =
    if (healthCheck.protocol != Protocol.COMMAND) {
      log.debug(
        "Scheduling next health check for app [{}] version [{}] and healthCheck [{}]",
        app.id,
        app.version,
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
    taskTracker.appTasksSync(app.id).foreach { task =>

      task.launched.foreach { launched =>
        if (launched.runSpecVersion == app.version && launched.hasStartedRunning && task.taskStatus != Unreachable) {
          log.debug("Dispatching health check job for {}", task.taskId)
          val worker: ActorRef = context.actorOf(workerProps)
          worker ! HealthCheckJob(app, task, launched, healthCheck)
        }
      }
    }
  }

  def checkConsecutiveFailures(task: Task, health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      log.info(
        s"Detected unhealthy ${task.taskId} of app [${app.id}] version [${app.version}] on host ${task.agentInfo.host}"
      )

      // kill the task, if it is reachable
      task.taskStatus match {
        case Unreachable =>
          val id = task.taskId
          log.warning(s"Task $id on host ${task.agentInfo.host} is temporarily unreachable. Performing no kill.")
        case _ =>
          marathonSchedulerDriverHolder.driver.foreach { driver =>
            log.warning(s"Send kill request for ${task.taskId} on host ${task.agentInfo.host} to driver")
            eventBus.publish(
              UnhealthyTaskKillEvent(
                appId = task.runSpecId,
                taskId = task.taskId,
                version = app.version,
                reason = health.lastFailureCause.getOrElse("unknown"),
                host = task.agentInfo.host,
                slaveId = task.agentInfo.agentId,
                timestamp = health.lastFailure.getOrElse(Timestamp.now()).toString
              )
            )
            driver.killTask(task.taskId.mesosTaskId)
          }
      }
    }
  }

  def ignoreFailures(task: Task, health: Health): Boolean = {
    // Ignore failures during the grace period, until the task becomes green
    // for the first time.  Also ignore failures while the task is staging.
    task.launched.fold(true) { launched =>
      health.firstSuccess.isEmpty &&
        launched.status.startedAt.fold(true) { startedAt =>
          startedAt + healthCheck.gracePeriod > Timestamp.now()
        }
    }
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

    case result: HealthResult if result.version == app.version =>
      log.info("Received health result for app [{}] version [{}]: [{}]", app.id, app.version, result)
      val taskId = result.taskId
      val health = taskHealth.getOrElse(taskId, Health(taskId))

      val newHealth = result match {
        case Healthy(_, _, _) =>
          health.update(result)
        case Unhealthy(_, _, _, _) =>
          taskTracker.tasksByAppSync.task(taskId) match {
            case Some(task) =>
              if (ignoreFailures(task, health)) {
                // Don't update health
                health
              } else {
                eventBus.publish(FailedHealthCheck(app.id, taskId, healthCheck))
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
            appId = app.id,
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
  def props(
    app: AppDefinition,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream): Props = {

    Props(new HealthCheckActor(
      app,
      marathonSchedulerDriverHolder,
      healthCheck,
      taskTracker,
      eventBus))
  }

  // self-sent every healthCheck.intervalSeconds
  case object Tick
  case class GetTaskHealth(taskId: Task.Id)
  case object GetAppHealth

  case class AppHealth(health: Seq[Health])
}
