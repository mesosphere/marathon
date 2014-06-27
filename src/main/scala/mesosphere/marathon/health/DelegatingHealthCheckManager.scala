package mesosphere.marathon.health

import mesosphere.marathon.api.v1.AppDefinition

import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

class DelegatingHealthCheckManager extends HealthCheckManager {

  protected[this] var taskHealth = Map[String, Health]()

  override def list(appId: String): Set[HealthCheck] = ???

  override def add(appId: String, healthCheck: HealthCheck): Unit = {}

  override def addAllFor(app: AppDefinition): Unit = {}

  override def remove(appId: String, healthCheck: HealthCheck): Unit = {}

  override def removeAll(): Unit = {}

  override def removeAllFor(appId: String): Unit = {}

  override def reconcileWith(app: AppDefinition): Unit = {}

  override def update(taskStatus: TaskStatus): Health = {
    val id = taskStatus.getTaskId.getValue
    val oldHealth: Health = ???
    val newHealth =
      if (taskStatus.hasHealthy) {
        val healthResult = taskStatus.getHealthy match {
          case true  => Healthy(taskId = id)
          case false => Unhealthy(taskId = id, cause = "")
        }
        oldHealth update healthResult
      }
      else oldHealth

    taskHealth = taskHealth + (id -> newHealth)
    newHealth
  }

  override def status(
    appId: String,
    taskId: String): Future[Seq[Option[Health]]] =
    Future.successful(Seq(taskHealth.get(taskId)))

}
