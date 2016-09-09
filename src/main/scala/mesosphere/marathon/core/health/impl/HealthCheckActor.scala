package mesosphere.marathon.core.health.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health.impl.HealthCheckActor._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }

private[health] class HealthCheckActor(
    app: AppDefinition,
    killService: TaskKillService,
    healthCheck: HealthCheck,
    taskTracker: InstanceTracker,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import HealthCheckWorker.HealthCheckJob

  var nextScheduledCheck: Option[Cancellable] = None
  var instanceHealth = Map[Instance.Id, Health]()

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
    val activeTaskIds = taskTracker.specInstancesLaunchedSync(app.id).map(_.id).toSet
    // The Map built with filterKeys wraps the original map and contains a reference to activeTaskIds.
    // Therefore we materialize it into a new map.
    instanceHealth = instanceHealth.filterKeys(activeTaskIds).iterator.toMap
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
    taskTracker.specInstancesSync(app.id).foreach {
      case task: Task =>
        task.launched.foreach { launched =>
          if (launched.runSpecVersion == app.version && task.isRunning) {
            log.debug("Dispatching health check job for {}", task.id)
            val worker: ActorRef = context.actorOf(workerProps)
            worker ! HealthCheckJob(app, task, launched, healthCheck)
          }
        }
      case _ => () // TODO POD support
    }
  }

  def checkConsecutiveFailures(task: Task, health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      log.info(
        s"Detected unhealthy ${task.id} of app [${app.id}] version [${app.version}] on host ${task.agentInfo.host}"
      )

      // kill the task, if it is reachable
      if (task.isUnreachable) {
        val id = task.id
        log.info(s"Task $id on host ${task.agentInfo.host} is temporarily unreachable. Performing no kill.")
      } else {
        log.info(s"Send kill request for ${task.id} on host ${task.agentInfo.host} to driver")
        eventBus.publish(
          UnhealthyTaskKillEvent(
            appId = task.runSpecId,
            taskId = task.id,
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

  def ignoreFailures(instance: Instance, health: Health): Boolean = {
    // Ignore failures during the grace period, until the task becomes green
    // for the first time.  Also ignore failures while the task is staging.
    Task(instance).map { task =>
      task.launched.fold(true) { launched =>
        health.firstSuccess.isEmpty &&
          launched.status.startedAt.fold(true) { startedAt =>
            startedAt + healthCheck.gracePeriod > Timestamp.now()
          }
      }
    }.getOrElse(false)
    // TODO(jdef) support health checks for pods
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def receive: Receive = {
    case GetInstanceHealth(taskId) => sender() ! instanceHealth.getOrElse(taskId, Health(taskId))

    case GetAppHealth =>
      sender() ! AppHealth(instanceHealth.values.toSeq)

    case Tick =>
      purgeStatusOfDoneTasks()
      dispatchJobs()
      scheduleNextHealthCheck()

    case result: HealthResult if result.version == app.version =>
      log.info("Received health result for app [{}] version [{}]: [{}]", app.id, app.version, result)
      val taskId = result.instanceId
      val health = instanceHealth.getOrElse(taskId, Health(taskId))

      val newHealth = result match {
        case Healthy(_, _, _) =>
          health.update(result)
        case Unhealthy(_, _, _, _) =>
          taskTracker.instancesBySpecSync.instanceFor(taskId) match {
            case Some(instance) =>
              if (ignoreFailures(instance, health)) {
                // Don't update health
                health
              } else {
                eventBus.publish(FailedHealthCheck(app.id, taskId, healthCheck))
                Task(instance).map { task =>
                  checkConsecutiveFailures(task, health)
                  health.update(result)
                }.getOrElse(health)
                // TODO(jdef) anything to do for pods? pretty sure that Mesos will kill pods that are unhealthy
              }
            case None =>
              log.error(s"Couldn't find task $taskId")
              health.update(result)
          }
      }

      instanceHealth += (taskId -> newHealth)

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
    killService: TaskKillService,
    healthCheck: HealthCheck,
    taskTracker: InstanceTracker,
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
  case class GetInstanceHealth(instanceId: Instance.Id)
  case object GetAppHealth

  case class AppHealth(health: Seq[Health])
}
