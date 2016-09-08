package mesosphere.marathon.core.health.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.health.impl.HealthCheckActor._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, Timestamp }

import scala.async.Async._
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

private[health] class HealthCheckActor(
    app: AppDefinition,
    killService: TaskKillService,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream) extends Actor with ActorLogging {

  import HealthCheckWorker.HealthCheckJob
  import context.dispatcher

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
    //Start health checking not after the default first health check
    val start = math.min(healthCheck.interval.toMillis, HealthCheck.DefaultFirstHealthCheckAfter.toMillis).millis
    scheduleNextHealthCheck(Some(start))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    log.info(
      "Restarting health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )

  override def postStop(): Unit = {
    nextScheduledCheck.forall {
      _.cancel()
    }
    log.info(
      "Stopped health check actor for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
  }

  def purgeStatusOfDoneTasks(tasks: Iterable[Task]): Unit = {
    log.debug(
      "Purging health status of done tasks for app [{}] version [{}] and healthCheck [{}]",
      app.id,
      app.version,
      healthCheck
    )
    val activeTaskIds = tasks.withFilter(_.launched.isDefined).map(_.taskId).toSet
    // The Map built with filterKeys wraps the original map and contains a reference to activeTaskIds.
    // Therefore we materialize it into a new map.
    taskHealth = taskHealth.filterKeys(activeTaskIds).iterator.toMap
  }

  def updateInstances(): Unit = {
    taskTracker.appTasks(app.id).onComplete {
      case Success(tasks) => self ! TasksUpdate(version = app.version, tasks = tasks)
      case Failure(t) => log.error("An error has occurred: " + t.getMessage, t)
    }
  }

  def scheduleNextHealthCheck(interval: Option[FiniteDuration] = None): Unit = healthCheck match {
    case hc: MarathonHealthCheck =>
      log.debug(
        "Scheduling next health check for app [{}] version [{}] and healthCheck [{}]",
        app.id,
        app.version,
        hc
      )
      nextScheduledCheck = Some(
        context.system.scheduler.scheduleOnce(interval.getOrElse(hc.interval)) {
          self ! Tick
        }
      )
    case _ => // Don't do anything for Mesos health checks
  }

  def dispatchJobs(tasks: Iterable[Task]): Unit = healthCheck match {
    case hc: MarathonHealthCheck =>
      log.debug("Dispatching health check jobs to workers")
      tasks.foreach { task =>
        task.launched.foreach { launched =>
          if (launched.runSpecVersion == app.version && task.isRunning) {
            log.debug("Dispatching health check job for {}", task.taskId)
            val worker: ActorRef = context.actorOf(workerProps)
            worker ! HealthCheckJob(app, task, launched, hc)
          }
        }
      }
    case _ => // Don't do anything for Mesos health checks
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
      if (task.isUnreachable) {
        val id = task.taskId
        log.info(s"Task $id on host ${task.agentInfo.host} is temporarily unreachable. Performing no kill.")
      } else {
        log.info(s"Send kill request for ${task.taskId} on host ${task.agentInfo.host} to driver")
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
        killService.killTask(task, TaskKillReason.FailedHealthChecks)
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

  def updateTaskHealth(th: TaskHealth): Unit = {
    val result = th.result
    val health = th.health
    val newHealth = th.newHealth
    val taskId = result.taskId

    log.info("Received health result for app [{}] version [{}]: [{}]", app.id, app.version, result)
    taskHealth += (taskId -> newHealth)

    if (health.alive != newHealth.alive && result.publishEvent) {
      eventBus.publish(
        HealthStatusChanged(
          appId = app.id,
          taskId = taskId,
          version = result.version,
          alive = newHealth.alive)
      )
    }
  }

  def handleHealthResult(result: HealthResult): Unit = {
    val taskId = result.taskId
    val health = taskHealth.getOrElse(taskId, Health(taskId))

    val updatedHealth = async {
      result match {
        case Healthy(_, _, _, _) =>
          health.update(result)
        case Unhealthy(_, _, _, _, _) =>
          await(taskTracker.tasksByApp).task(taskId) match {
            case Some(task) =>
              if (ignoreFailures(task, health)) {
                // Don't update health
                health
              } else {
                if (result.publishEvent) {
                  eventBus.publish(FailedHealthCheck(app.id, taskId, healthCheck))
                }
                checkConsecutiveFailures(task, health)
                health.update(result)
              }
            case None =>
              log.error(s"Couldn't find task $taskId")
              health.update(result)
          }
      }
    }
    updatedHealth.onComplete {
      case Success(newHealth) => self ! TaskHealth(result, health, newHealth)
      case Failure(t) => log.error("An error has occurred: " + t.getMessage, t)
    }
  }

  def receive: Receive = {
    case GetTaskHealth(taskId) => sender() ! taskHealth.getOrElse(taskId, Health(taskId))

    case GetAppHealth =>
      sender() ! AppHealth(taskHealth.values.toSeq)

    case Tick =>
      updateInstances()
      scheduleNextHealthCheck()

    case TasksUpdate(version, tasks) if version == app.version =>
      purgeStatusOfDoneTasks(tasks)
      dispatchJobs(tasks)

    case taskHealth: TaskHealth =>
      updateTaskHealth(taskHealth)

    case result: HealthResult if result.version == app.version =>
      handleHealthResult(result)

    case result: HealthResult =>
      log.warning(s"Ignoring health result [$result] due to version mismatch.")
  }
}

object HealthCheckActor {
  def props(
    app: AppDefinition,
    killService: TaskKillService,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream): Props = {

    Props(new HealthCheckActor(
      app,
      killService,
      healthCheck,
      taskTracker,
      eventBus))
  }

  // self-sent every healthCheck.intervalSeconds
  case object Tick
  case class GetTaskHealth(taskId: Task.Id)
  case object GetAppHealth

  case class AppHealth(health: Seq[Health])

  case class TaskHealth(result: HealthResult, health: Health, newHealth: Health)

  case class TasksUpdate(version: Timestamp, tasks: Iterable[Task])

}
