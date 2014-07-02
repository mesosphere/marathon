package mesosphere.marathon.health

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.event._
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol

import com.google.common.eventbus.EventBus
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future
import javax.inject.{ Named, Inject }

class DelegatingHealthCheckManager @Inject() (
  @Named(EventModule.busName) eventBus: Option[EventBus])
    extends HealthCheckManager {

  protected[this] var taskHealth = Map[String, Health]()

  protected[this] var healthChecks = Map[String, Set[HealthCheck]]()

  override def list(appId: String): Set[HealthCheck] =
    healthChecks.getOrElse(appId, Set[HealthCheck]())

  override def add(appId: String, healthCheck: HealthCheck): Unit =
    healthChecks = healthChecks + (appId -> (list(appId) + healthCheck))

  override def addAllFor(app: AppDefinition): Unit =
    healthChecks = healthChecks + (app.id -> app.healthChecks)

  override def remove(appId: String, healthCheck: HealthCheck): Unit =
    healthChecks = healthChecks + (appId -> (list(appId) - healthCheck))

  override def removeAll(): Unit =
    healthChecks = Map[String, Set[HealthCheck]]()

  override def removeAllFor(appId: String): Unit =
    healthChecks = healthChecks - appId

  override def reconcileWith(app: AppDefinition): Unit = addAllFor(app)

  override def update(taskStatus: TaskStatus): Health = {
    val taskId = taskStatus.getTaskId.getValue
    val oldHealth: Health = taskHealth.getOrElse(taskId, Health(taskId))
    val newHealth =
      if (taskStatus.hasHealthy) {
        val healthy = taskStatus.getHealthy()
        log.info(s"Received status for [$taskId] with healthy=[$healthy]")
        log.debug(s"TaskStatus:\n$taskStatus")
        val healthResult = healthy match {
          case true  => Healthy(taskId = taskId)
          case false => Unhealthy(taskId = taskId, cause = "")
        }
        oldHealth update healthResult
      }
      else {
        log.info(s"Ignoring status for [$taskId] with no health information")
        log.debug(s"TaskStatus:\n$taskStatus")
        oldHealth
      }

    val appId = taskId take taskId.lastIndexOf('.')

    if (!newHealth.alive())
      firstCommandCheck(appId).foreach { healthCheck =>
        eventBus.foreach(_.post(FailedHealthCheck(appId, taskId, healthCheck)))
      }

    taskHealth = taskHealth + (taskId -> newHealth)
    newHealth
  }

  override def status(
    appId: String,
    taskId: String): Future[Seq[Option[Health]]] =
    Future.successful(Seq(taskHealth.get(taskId)))

  private[this] def firstCommandCheck(appId: String): Option[HealthCheck] =
    healthChecks.get(appId).flatMap {
      _.collectFirst { case hc if hc.protocol == Protocol.COMMAND => hc }
    }

}
