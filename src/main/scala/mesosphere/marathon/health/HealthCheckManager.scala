package mesosphere.marathon.health

import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }

import org.apache.log4j.Logger
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future

trait HealthCheckManager {

  protected[this] val log = Logger.getLogger(getClass.getName)

  /**
    * Returns the active health checks for the app with the supplied id.
    */
  def list(appId: PathId): Set[HealthCheck]

  /**
    * Adds a health check for the app with the supplied id.
    */
  def add(appId: PathId, version: Timestamp, healthCheck: HealthCheck): Unit

  /**
    * Adds all health checks for the supplied app.
    */
  def addAllFor(app: AppDefinition): Unit

  /**
    * Removes a health check from the app with the supplied id.
    */
  def remove(appId: PathId, version: Timestamp, healthCheck: HealthCheck): Unit

  /**
    * Removes all health checks.
    */
  def removeAll(): Unit

  /**
    * Removes all health checks for the app with the supplied id.
    */
  def removeAllFor(appId: PathId): Unit

  /**
    * Reconciles active health checks with those defined by the supplied app.
    */
  def reconcileWith(appId: PathId): Future[Unit]

  /**
    * Notifies this health check manager of health information received
    * from Mesos.
    */
  def update(taskStatus: TaskStatus, version: Timestamp): Unit

  /**
    * Returns the health status of the supplied task.
    */
  def status(appId: PathId, taskId: String): Future[Seq[Option[Health]]]

  /**
    * Returns the health status of all tasks of the supplied app.
    */
  def statuses(appId: PathId): Future[Map[String, Seq[Health]]]

  /**
    * Returns the health status counter for the supplied app.
    */
  def healthCounts(
    appId: PathId): Future[HealthCounts]
}
