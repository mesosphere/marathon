package mesosphere.marathon.health

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import akka.actor.{ActorRef, ActorSystem, Props}
import javax.inject.Inject

case class ActiveHealthCheck(healthCheck: HealthCheck, actor: ActorRef)

class HealthCheckManager @Inject() (system: ActorSystem, taskTracker: TaskTracker) {

  import HealthCheckActor.Health

  protected[this] var appHealthChecks = Map[String, Set[ActiveHealthCheck]]()

  def list(appId: String): Set[HealthCheck] =
    appHealthChecks.get(appId) match {
      case Some(activeHealthChecks) => activeHealthChecks.map(_.healthCheck)
      case None => Set[HealthCheck]()
    }

  protected[this] def find(
    appId: String,
    healthCheck: HealthCheck
  ): Option[ActiveHealthCheck] =
    appHealthChecks.get(appId).flatMap {
      _.find { _.healthCheck == healthCheck }
    }

  def add(appId: String, healthCheck: HealthCheck): Unit = {
    val healthChecksForApp =
      appHealthChecks.get(appId).getOrElse(Set[ActiveHealthCheck]())

    if (! healthChecksForApp.exists { _.healthCheck == healthCheck }) {
      val ref = system.actorOf(
        Props(new HealthCheckActor(appId, healthCheck, taskTracker))
      )
      val newHealthChecksForApp =
        healthChecksForApp + ActiveHealthCheck(healthCheck, ref)
      appHealthChecks = appHealthChecks + (appId -> newHealthChecksForApp)
    }
  }

  def addAllFor(app: AppDefinition): Unit =
    for (healthCheck <- app.healthChecks) add(app.id, healthCheck)

  def remove(appId: String, healthCheck: HealthCheck): Unit =
    for (activeHealthChecks <- appHealthChecks.get(appId)) {
      activeHealthChecks.find(_.healthCheck == healthCheck) foreach deactivate

      val newHealthChecksForApp =
        activeHealthChecks.filterNot { _.healthCheck == healthCheck }

      appHealthChecks =
        if (newHealthChecksForApp.isEmpty) appHealthChecks - appId
        else appHealthChecks + (appId -> newHealthChecksForApp)
    }

  def removeAllFor(appId: String): Unit =
    for (activeHealthChecks <- appHealthChecks.get(appId))
      activeHealthChecks.foreach { ahc => remove(appId, ahc.healthCheck) }

  def reconcileWith(app: AppDefinition): Unit = {
    val existingHealthChecks = list(app.id)
    val toRemove = existingHealthChecks -- app.healthChecks
    val toAdd = app.healthChecks -- existingHealthChecks
    for (hc <- toRemove) remove(app.id, hc)
    for (hc <- toAdd) add(app.id, hc)
  }

  def status(appId: String, taskId: String): Seq[Option[Health]] = {
    ??? // TODO(CD)
  }

  def status(appId: String): Map[String, Seq[Option[Health]]] = {
    ??? // TODO(CD)
  }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    system stop healthCheck.actor

}
