package mesosphere.marathon.health

import akka.actor.ActorRef
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }

import org.apache.mesos.Protos.TaskStatus
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.collection.immutable.{ Seq, Map }

trait HealthCheckManager {

  protected[this] val log = LoggerFactory.getLogger(getClass.getName)

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
    * Requests the health of all tasks of the supplied appId and appVersion.
    * Sends an AppHealth message to requester.
    */
  def requestHealth(appId: PathId, appVersion: Timestamp, requester: ActorRef): Unit

  /**
    * Returns the health status of the supplied task.
    */
  def status(appId: PathId, taskId: String): Future[Seq[Health]]

  /**
    * Returns the health status of all tasks of the supplied app.
    */
  def statuses(appId: PathId): Future[Map[String, Seq[Health]]]
}
