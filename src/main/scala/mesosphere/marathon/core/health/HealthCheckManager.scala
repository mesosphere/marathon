package mesosphere.marathon
package core.health

import akka.Done
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp}
import org.apache.mesos.Protos.TaskStatus

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future

trait HealthCheckManager {

  /**
    * Returns the active health checks for the app with the supplied id.
    */
  def list(appId: AbsolutePathId): Set[HealthCheck]

  /**
    * Adds a health check of the supplied app.
    */
  def add(appDefinition: AppDefinition, healthCheck: HealthCheck, instances: Seq[Instance]): Unit

  /**
    * Adds all health checks for the supplied app.
    */
  def addAllFor(app: AppDefinition, instances: Seq[Instance]): Unit

  /**
    * Removes a health check from the app with the supplied id.
    */
  def remove(appId: AbsolutePathId, version: Timestamp, healthCheck: HealthCheck): Unit

  /**
    * Removes all health checks.
    */
  def removeAll(): Unit

  /**
    * Removes all health checks for the app with the supplied id.
    */
  def removeAllFor(appId: AbsolutePathId): Unit

  /**
    * Reconciles active health checks with those defined for all supplied apps.
    */
  def reconcile(apps: Seq[AppDefinition]): Future[Done]

  /**
    * Notifies this health check manager of health information received
    * from Mesos.
    */
  def update(taskStatus: TaskStatus, version: Timestamp): Unit

  /**
    * Returns the health status of the supplied instance.
    */
  def status(appId: AbsolutePathId, instanceId: Instance.Id): Future[Seq[Health]]

  /**
    * Returns the health status of all instances of the supplied app.
    */
  def statuses(appId: AbsolutePathId): Future[Map[Instance.Id, Seq[Health]]]
}
