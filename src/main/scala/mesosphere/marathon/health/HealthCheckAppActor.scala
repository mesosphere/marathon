package mesosphere.marathon.health

import javax.inject.Named
import akka.actor.{ ActorRef, Props, ActorLogging, Actor }
import akka.event.EventStream
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonScheduler }
import mesosphere.marathon.event.{ EventModule, AddHealthCheck }
import mesosphere.marathon.state.{ Timestamp, PathId, AppDefinition }
import scala.collection.mutable

/**
  * Parent actor for that holds all health checks for the app ([[AppDefinition]]).
  */
class HealthCheckAppActor(app: AppDefinition,
                          scheduler: MarathonScheduler,
                          marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
                          @Named(EventModule.busName) eventBus: EventStream,
                          taskTracker: TaskTracker) extends Actor with ActorLogging {

  case class ActiveHealthCheck(healthCheck: HealthCheck,
                               actor: ActorRef)

  case class HealthCheckResult(previousHealthCheckResult: Option[Health],
                               currentHealthCheckResult: Health)

  protected val taskHealth = mutable.Map.empty[TaskId, mutable.Map[HealthCheck, HealthCheckResult]]

  protected val healthChecks = mutable.Set.empty[ActiveHealthCheck]

  override def preStart: Unit = {
    super.preStart
    addAllFor(app)
  }

  override def receive: Receive = {
    case taskHealthChange: HealthCheckActor.TaskHealthChange => {
      accumulateTaskHealthUpdate(taskHealthChange)
    }
  }

  protected def accumulateTaskHealthUpdate(taskHealthChange: HealthCheckActor.TaskHealthChange): Unit = {

    val healthCheckResult = HealthCheckResult(taskHealthChange.previousHealth, taskHealthChange.currentHealth)

    val newSubMap: mutable.Map[HealthCheck, HealthCheckResult] = taskHealth.get(taskHealthChange.taskId) match {

      case Some(healthCheckResults) => {
        healthCheckResults.put(
          taskHealthChange.healthCheck,
          healthCheckResult
        )
        healthCheckResults
      }

      case None => mutable.Map(
        taskHealthChange.healthCheck ->
          healthCheckResult
      )
    }

    taskHealth.put(taskHealthChange.taskId, newSubMap)
  }

  /**
    * Spawns child actor that will take care about one single [[HealthCheck]].
    */
  protected def add(appId: PathId, appVersion: Timestamp, healthCheck: HealthCheck): Unit =
    if (healthChecks.exists(_.healthCheck == healthCheck))
      log.debug(s"Not adding duplicate health check for app [$appId] and version [$appVersion]: [$healthCheck]")

    else {
      log.info(s"Adding health check for app [$appId] and version [$appVersion]: [$healthCheck]")

      val props = Props(new HealthCheckActor(
        appId, appVersion.toString, marathonSchedulerDriverHolder, scheduler, healthCheck, taskTracker, eventBus,
        Some(self))
      )

      val childActor = context.actorOf(props, s"$appId#${healthCheck.getClass.getSimpleName}")

      healthChecks += ActiveHealthCheck(healthCheck, childActor)

      eventBus.publish(AddHealthCheck(appId, appVersion, healthCheck))
    }

  protected def addAllFor(app: AppDefinition): Unit = app.healthChecks.foreach(add(app.id, app.version, _))

}
