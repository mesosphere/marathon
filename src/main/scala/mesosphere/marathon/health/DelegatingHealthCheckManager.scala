package mesosphere.marathon.health

import javax.inject.{ Inject, Named }
import scala.concurrent.Future

import akka.event.EventStream
import org.apache.mesos.Protos.TaskStatus

import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskIDUtil

class DelegatingHealthCheckManager @Inject() (
  @Named(EventModule.busName) eventBus: EventStream)
    extends HealthCheckManager {

  protected[this] var taskHealth = Map[String, Health]()

  protected[this] var healthChecks = Map[PathId, Set[HealthCheck]]()

  override def list(appId: PathId): Set[HealthCheck] =
    healthChecks.getOrElse(appId, Set[HealthCheck]())

  override def add(appId: PathId, healthCheck: HealthCheck): Unit =
    healthChecks = healthChecks + (appId -> (list(appId) + healthCheck))

  override def addAllFor(app: AppDefinition): Unit =
    healthChecks = healthChecks + (app.id -> app.healthChecks)

  override def remove(appId: PathId, healthCheck: HealthCheck): Unit =
    healthChecks = healthChecks + (appId -> (list(appId) - healthCheck))

  override def removeAll(): Unit =
    healthChecks = Map[PathId, Set[HealthCheck]]()

  override def removeAllFor(appId: PathId): Unit =
    healthChecks = healthChecks - appId

  override def reconcileWith(app: AppDefinition): Unit = addAllFor(app)

  override def update(taskStatus: TaskStatus, version: String): Unit = {
    val taskId = taskStatus.getTaskId.getValue
    val oldHealth: Health = taskHealth.getOrElse(taskId, Health(taskId))
    val newHealth =
      if (taskStatus.hasHealthy) {
        val healthy = taskStatus.getHealthy
        log.info(s"Received status for [$taskId] with healthy=[$healthy]")
        log.debug(s"TaskStatus:\n$taskStatus")
        val healthResult = healthy match {
          case true  => Healthy(taskId, version)
          case false => Unhealthy(taskId, version, "")
        }
        oldHealth update healthResult
      }
      else {
        log.info(s"Ignoring status for [$taskId] with no health information")
        log.debug(s"TaskStatus:\n$taskStatus")
        oldHealth
      }

    val appId = TaskIDUtil.appID(taskStatus.getTaskId)

    for (healthCheck <- firstCommandCheck(appId)) {
      if (!newHealth.alive) eventBus.publish(FailedHealthCheck(appId, taskId, healthCheck))
      if (newHealth.alive != oldHealth.alive) eventBus.publish(HealthStatusChanged(appId, taskId, version, newHealth.alive))
    }

    taskHealth = taskHealth + (taskId -> newHealth)
  }

  override def status(
    appId: PathId,
    taskId: String): Future[Seq[Option[Health]]] =
    Future.successful(Seq(taskHealth.get(taskId)))

  private[this] def firstCommandCheck(appId: PathId): Option[HealthCheck] =
    healthChecks.get(appId).flatMap {
      _.collectFirst { case hc if hc.protocol == Protocol.COMMAND => hc }
    }

}
