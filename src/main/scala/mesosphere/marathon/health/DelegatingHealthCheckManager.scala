package mesosphere.marathon.health

import javax.inject.{ Inject, Named }
import scala.concurrent.Future

import akka.event.EventStream
import org.apache.mesos.Protos.TaskStatus

import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskIdUtil

class DelegatingHealthCheckManager @Inject() (
    @Named(EventModule.busName) eventBus: EventStream,
    taskIdUtil: TaskIdUtil) extends HealthCheckManager {

  protected[this] var taskHealth = Map[String, Health]()

  protected[this] var healthChecks = Map[PathId, Set[HealthCheck]]()

  override def list(appId: PathId): Set[HealthCheck] =
    healthChecks.getOrElse(appId, Set[HealthCheck]())

  override def add(appId: PathId, healthCheck: HealthCheck): Unit =
    healthChecks = healthChecks + (appId -> (list(appId) + healthCheck))

  override def addAllFor(app: AppDefinition): Unit =
    app.healthChecks foreach { hc => add(app.id, hc) }

  override def remove(appId: PathId, healthCheck: HealthCheck): Unit =
    healthChecks = healthChecks + (appId -> (list(appId) - healthCheck))

  override def removeAll(): Unit =
    healthChecks = Map[PathId, Set[HealthCheck]]()

  override def removeAllFor(appId: PathId): Unit =
    healthChecks = healthChecks - appId

  override def reconcileWith(app: AppDefinition): Unit = {
    removeAllFor(app.id)
    addAllFor(app)
  }

  override def update(taskStatus: TaskStatus, version: String): Unit = {
    val taskId = taskStatus.getTaskId.getValue
    val oldHealth: Option[Health] = taskHealth.get(taskId)
    val newHealth: Option[Health] =
      if (taskStatus.hasHealthy) {
        val healthy = taskStatus.getHealthy
        log.info(s"Received status for [$taskId] with healthy=[$healthy]")
        log.debug(s"TaskStatus:\n$taskStatus")
        val healthResult = healthy match {
          case true  => Healthy(taskId, version)
          case false => Unhealthy(taskId, version, "")
        }
        oldHealth match {
          case Some(health) => Some(health update healthResult)
          case None         => Some(Health(taskId) update healthResult)
        }
      }
      else {
        log.info(s"Ignoring status for [$taskId] with no health information")
        log.debug(s"TaskStatus:\n$taskStatus")
        None
      }

    val appId = taskIdUtil.appId(taskStatus.getTaskId)
    val c = firstCommandCheck(appId)

    for {
      healthCheck <- c
      hPrime <- newHealth
    } {
      if (!hPrime.alive) {
        eventBus.publish(FailedHealthCheck(appId, taskId, healthCheck))
      }
      if (oldHealth.isEmpty || hPrime.alive != oldHealth.get.alive) {
        eventBus.publish(HealthStatusChanged(appId, taskId, version, hPrime.alive))
      }

      taskHealth = taskHealth + (taskId -> hPrime)
    }
  }

  override def status(
    appId: PathId,
    taskId: String): Future[Seq[Option[Health]]] =
    Future.successful(Seq(taskHealth.get(taskId)))

  private[this] def firstCommandCheck(appId: PathId): Option[HealthCheck] =
    healthChecks.get(appId).flatMap {
      _.collectFirst {
        case hc if hc.protocol == Protocol.COMMAND => hc
      }
    }

}
