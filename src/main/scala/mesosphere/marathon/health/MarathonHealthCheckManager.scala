package mesosphere.marathon.health

import javax.inject.{ Inject, Named }
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import akka.pattern.ask
import akka.util.Timeout
import org.apache.mesos.Protos.TaskStatus

import mesosphere.marathon.event.{ AddHealthCheck, EventModule, RemoveHealthCheck }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.util.ThreadPoolContext.context

class MarathonHealthCheckManager @Inject() (
    system: ActorSystem,
    @Named(EventModule.busName) eventBus: EventStream,
    taskTracker: TaskTracker) extends HealthCheckManager {

  protected[this] case class ActiveHealthCheck(
    healthCheck: HealthCheck,
    actor: ActorRef)

  protected[this] var appHealthChecks = Map[PathId, Set[ActiveHealthCheck]]()

  override def list(appId: PathId): Set[HealthCheck] =
    appHealthChecks.get(appId).fold(Set[HealthCheck]()) { activeHealthChecks =>
      activeHealthChecks.map(_.healthCheck)
    }

  protected[this] def find(
    appId: PathId,
    healthCheck: HealthCheck): Option[ActiveHealthCheck] =
    appHealthChecks.get(appId).flatMap {
      _.find { _.healthCheck == healthCheck }
    }

  override def add(appId: PathId, healthCheck: HealthCheck): Unit = {
    val healthChecksForApp =
      appHealthChecks.get(appId).getOrElse(Set[ActiveHealthCheck]())

    if (!healthChecksForApp.exists { _.healthCheck == healthCheck }) {
      val ref = system.actorOf(
        Props(classOf[HealthCheckActor], appId, healthCheck, taskTracker, eventBus)
      )
      val newHealthChecksForApp =
        healthChecksForApp + ActiveHealthCheck(healthCheck, ref)
      appHealthChecks += (appId -> newHealthChecksForApp)
    }

    eventBus.publish(AddHealthCheck(appId, healthCheck))
  }

  override def addAllFor(app: AppDefinition): Unit =
    app.healthChecks.foreach(add(app.id, _))

  override def remove(appId: PathId, healthCheck: HealthCheck): Unit = {
    for (activeHealthChecks <- appHealthChecks.get(appId)) {
      activeHealthChecks.find(_.healthCheck == healthCheck) foreach deactivate

      val newHealthChecksForApp =
        activeHealthChecks.filterNot { _.healthCheck == healthCheck }

      appHealthChecks =
        if (newHealthChecksForApp.isEmpty) appHealthChecks - appId
        else appHealthChecks + (appId -> newHealthChecksForApp)
    }

    eventBus.publish(RemoveHealthCheck(appId))
  }

  override def removeAll(): Unit = appHealthChecks.keys foreach removeAllFor

  override def removeAllFor(appId: PathId): Unit =
    for (activeHealthChecks <- appHealthChecks.get(appId)) {
      activeHealthChecks foreach deactivate
      appHealthChecks = appHealthChecks - appId
    }

  override def reconcileWith(app: AppDefinition): Unit = {
    val existingHealthChecks = list(app.id)
    val toRemove = existingHealthChecks -- app.healthChecks
    val toAdd = app.healthChecks -- existingHealthChecks
    for (hc <- toRemove) remove(app.id, hc)
    for (hc <- toAdd) add(app.id, hc)
  }

  override def update(taskStatus: TaskStatus, version: String): Unit = {}

  override def status(
    appId: PathId,
    taskId: String): Future[Seq[Option[Health]]] = {
    import mesosphere.marathon.health.HealthCheckActor.GetTaskHealth
    implicit val timeout: Timeout = Timeout(2, SECONDS)
    appHealthChecks.get(appId) match {
      case Some(activeHealthCheckSet) => Future.sequence(
        activeHealthCheckSet.toSeq.map {
          case ActiveHealthCheck(_, actor) => (actor ? GetTaskHealth(taskId)).mapTo[Option[Health]]
        }
      )
      case None => Future.successful(Nil)
    }
  }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    system stop healthCheck.actor

}
