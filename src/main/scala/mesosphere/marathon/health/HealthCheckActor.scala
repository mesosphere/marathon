package mesosphere.marathon.health

import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.state.Timestamp
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import akka.routing.{RoundRobinRouter, DefaultResizer}
import mesosphere.marathon.api.validation.FieldConstraints._
import scala.Some
import com.fasterxml.jackson.annotation.JsonInclude.Include

class HealthCheckActor(
  appId: String,
  healthCheck: HealthCheck,
  taskTracker: TaskTracker
) extends Actor with ActorLogging {

  import HealthCheckActor.{GetHealth, GetTaskHealth, Health}
  import HealthCheckWorker.{HealthCheckJob, HealthResult}
  import context.dispatcher // execution context

  protected[this] var nextScheduledCheck: Option[Cancellable] = None

  protected[this] var taskHealth = Map[String, Health]()

  override def preStart(): Unit = {
    log.info(
      "Starting health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
    context.system.scheduler.scheduleOnce(healthCheck.initialDelay) {
      self ! Tick
    }
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
    taskHealth = taskHealth.filterKeys { activeTaskIds contains _ }
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

  def receive = {
    case GetHealth => sender ! taskHealth
    case GetTaskHealth(taskId) => sender ! taskHealth.get(taskId)
    case Tick => {
      purgeStatusOfDoneTasks()
      dispatchJobs()
      scheduleNextHealthCheck()
    }
    case result: HealthResult => {
      log.info("Received health result: [{}]", result)
      val taskId = result.taskId
      val health = taskHealth.getOrElse(taskId, Health(taskId)).update(result)
      taskHealth = taskHealth + (taskId -> health)
    }
  }

}

object HealthCheckActor {

  case object GetHealth

  case class GetTaskHealth(taskId: String)

  case class Health(
    taskId: String,
    lastSuccess: Option[Timestamp] = None,
    lastFailure: Option[Timestamp] = None,
    @FieldJsonInclude(Include.NON_NULL)
    lastFailureCause: Option[String] = None,
    consecutiveFailures: Int = 0
  ) {
    import HealthCheckWorker.{HealthResult, Healthy, Unhealthy}

    def update(result: HealthResult): Health = result match {
      case Healthy(_, time) => this.copy(
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