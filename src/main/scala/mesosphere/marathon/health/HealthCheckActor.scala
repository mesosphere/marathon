package mesosphere.marathon.health

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.tasks.TaskTracker
import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonInclude.Include
import mesosphere.marathon.event._
import mesosphere.marathon.MarathonSchedulerDriver
import akka.event.EventStream
import mesosphere.mesos.protos.TaskID
import mesosphere.marathon.Protos.MarathonTask

class HealthCheckActor(
    appId: String,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
  eventBus: EventStream

  import HealthCheckActor.{ GetTaskHealth, Health }
  import HealthCheckWorker.{ HealthCheckJob, HealthResult, Healthy, Unhealthy }
  import context.dispatcher // execution context
  import mesosphere.mesos.protos.Implicits._

  protected[this] var nextScheduledCheck: Option[Cancellable] = None

  protected[this] var taskHealth = Map[String, Health]()

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
    log.debug("Purging status of done tasks")
    val activeTaskIds = taskTracker.get(appId).map(_.getId)
    taskHealth = taskHealth.filterKeys(activeTaskIds)
  }

  protected[this] def scheduleNextHealthCheck(): Unit = {
    log.debug("Scheduling next health check")
    nextScheduledCheck = Some(
      context.system.scheduler.scheduleOnce(healthCheck.interval) {
        self ! Tick
      }
    )
  }

  protected[this] def dispatchJobs(): Unit = {
    log.debug("Dispatching health check jobs to workers")
    taskTracker.get(appId).foreach { task =>
      log.debug("Dispatching health check job for task [{}]", task.getId)
      val worker: ActorRef = context.actorOf(Props[HealthCheckWorkerActor])
      worker ! HealthCheckJob(task, healthCheck)
    }
  }

  protected[this] def checkConsecutiveFailures(task: MarathonTask,
                                               health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    if (consecutiveFailures >= maxFailures) {
      log.info(f"Killing task ${task.getId} on host ${task.getHost}")

      MarathonSchedulerDriver.driver.foreach { driver =>
        driver.killTask(TaskID(task.getId))
      }
    }
  }

  protected[this] def ignoreFailures(task: MarathonTask,
                                     health: Health): Boolean = {
    // Ignore failures during the grace period, until the task becomes green
    // for the first time.
    health.firstSuccess.isEmpty &&
      task.getStartedAt + healthCheck.gracePeriod.toMillis > System.currentTimeMillis()
  }

  def receive = {
    case GetTaskHealth(taskId) => sender ! taskHealth.get(taskId)
    case Tick =>
      purgeStatusOfDoneTasks()
      dispatchJobs()
      scheduleNextHealthCheck()

    case result: HealthResult =>
      log.info("Received health result: [{}]", result)
      val taskId = result.taskId
      val health = taskHealth.getOrElse(taskId, Health(taskId))

      val newHealth = result match {
        case Healthy(_, _) =>
          health.update(result)
        case Unhealthy(_, _, _) =>
          taskTracker.get(appId).find(_.getId == taskId) match {
            case Some(task) =>
              if (ignoreFailures(task, health)) {
                // Don't update health
                health
              }
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

      if (health.alive() != newHealth.alive()) {
        eventBus.publish(
          HealthStatusChanged(
            appId = appId,
            taskId = taskId,
            alive = newHealth.alive())
        )
      }
  }
}

object HealthCheckActor {

  case class GetTaskHealth(taskId: String)

  case class Health(
      taskId: String,
      firstSuccess: Option[Timestamp] = None,
      lastSuccess: Option[Timestamp] = None,
      lastFailure: Option[Timestamp] = None,
      @FieldJsonInclude(Include.NON_NULL) lastFailureCause: Option[String] = None,
      consecutiveFailures: Int = 0) {
    import HealthCheckWorker.{ HealthResult, Healthy, Unhealthy }

    @JsonProperty
    def alive(): Boolean = lastSuccess.exists { successTime =>
      lastFailure.isEmpty || successTime > lastFailure.get
    }

    def update(result: HealthResult): Health = result match {
      case Healthy(_, time) => this.copy(
        firstSuccess = this.firstSuccess.orElse(Some(time)),
        lastSuccess = Some(time),
        consecutiveFailures = 0
      )
      case Unhealthy(_, time, cause) => this.copy(
        lastFailure = Some(time),
        lastFailureCause = Some(cause),
        consecutiveFailures = this.consecutiveFailures + 1
      )
    }
  }

}
