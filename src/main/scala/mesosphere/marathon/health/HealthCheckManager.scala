package mesosphere.marathon.health

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskTracker

import org.apache.log4j.Logger
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

trait HealthCheckManager {

  protected[this] val log = Logger.getLogger(getClass.getName)

  /**
    * Returns the active health checks for the app with the supplied id.
    */
  def list(appId: String): Set[HealthCheck]

  /**
    * Adds a health check for the app with the supplied id.
    */
  def add(appId: String, healthCheck: HealthCheck): Unit

  /**
    * Adds all health checks for the supplied app.
    */
  def addAllFor(app: AppDefinition): Unit

  /**
    * Removes a health check from the app with the supplied id.
    */
  def remove(appId: String, healthCheck: HealthCheck): Unit

  /**
    * Removes all health checks.
    */
  def removeAll(): Unit

  /**
    * Removes all health checks for the app with the supplied id.
    */
  def removeAllFor(appId: String): Unit

  /**
    * Reconciles active health checks with those defined by the supplied app.
    */
  def reconcileWith(app: AppDefinition): Unit

  /**
    * Notifies this health check manager of health information received
    * from Mesos.
    */
  def update(taskStatus: TaskStatus): Unit

  /**
    * Returns the health status of the supplied task.
    */
  def status(appId: String, taskId: String): Future[Seq[Option[Health]]]

}
