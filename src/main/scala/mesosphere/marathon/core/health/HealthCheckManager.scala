package mesosphere.marathon
package core.health

import akka.Done
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future
import scala.collection.immutable.{ Map, Seq }

trait HealthCheckManager {

  /**
    * Returns the active health checks for the app with the supplied id.
    */
  def list(appId: PathId): Set[HealthCheck]

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
  def status(appId: PathId, instanceId: Instance.Id): Future[Seq[Health]]

  /**
    * Returns the health status of all instances of the supplied app.
    */
  def statuses(appId: PathId): Future[Map[Instance.Id, Seq[Health]]]
}
