package mesosphere.marathon
package core.health.impl

import akka.actor.{ Actor, Props }
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.InstanceHealthChanged
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.{
  AddHealthCheck,
  RemoveHealthCheck,
  PurgeHealthCheckStatuses,
  HealthCheckStatusChanged,
  AppHealthCheckProxy,
  ApplicationKey
}
import mesosphere.marathon.core.health.{ Health, HealthCheck }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.collection.generic.Subtractable
import scala.collection.mutable

/**
  * Actor computing instance healthiness of all instances of each application
  * in order to send `InstanceHealthChanged` events to the event bus when the healthiness
  * of a given instance changes. It basically aggregates all statuses of a given instance.
  *
  * There are 3 possible healthiness states:
  * - `Unknown` if at least one health check status of the instance is unknown
  * - `Unhealthy` if all statuses of the instance are known but at least one is failing
  * - `Healthy` if all statuses of the instance are known and all are healthy
  */
object AppHealthCheckActor {
  case class ApplicationKey(appId: PathId, version: Timestamp)
  case class InstanceKey(applicationKey: ApplicationKey, instanceId: Instance.Id)

  def props(eventBus: EventStream): Props = Props(new AppHealthCheckActor(eventBus))

  /**
    * Actor command adding an health check definition for a given application so that the actor knows which health check
    * statuses are know and which are not for a given instance.
    * @param appKey The application key of the application for which the health check is defined
    * @param healthCheck The definition of the health check to add
    */
  case class AddHealthCheck(appKey: ApplicationKey, healthCheck: HealthCheck)

  /**
    * Actor command removing a health check definition for a given application.
    * This method also purges the statuses of health checks of all instances that ran it.
    * @param appKey The application key of the application for which the health check is removed
    * @param healthCheck The definition of the health check to remove
    */
  case class RemoveHealthCheck(appKey: ApplicationKey, healthCheck: HealthCheck)

  /**
    * Actor command purging several health check statuses.
    * @param toPurge The list of health check statuses to purge.
    */
  case class PurgeHealthCheckStatuses(toPurge: Seq[(InstanceKey, HealthCheck)])

  /**
    * Actor command updating the status of an health check of a given instance of an application. If the instance status
    * has changed, it sends a `InstanceHealthChanged` event to the event bus.
    * @param appKey The application key for which the health check status is updated
    * @param healthCheck The health check for which the status is updated
    * @param healthState The new state of the health check
    */
  case class HealthCheckStatusChanged(
      appKey: ApplicationKey,
      healthCheck: HealthCheck, healthState: Health)

  /**
    * This proxy class is the implementation of the actor. It has been created to be used in performance benchmarks.
    * Beware this class is NOT thread safe.
    */
  class AppHealthCheckProxy extends StrictLogging {
    /**
      * Map of health check definitions of all applications
      */
    private[impl] val healthChecks: mutable.Map[ApplicationKey, Set[HealthCheck]] = mutable.Map.empty

    /**
      *  Map of statuses of all health checks for all instances of all applications.
      *  Statuses are optional, therefore the global health status of an instance
      *  is either:
      *    unknown (if some results are still missing)
      *    healthy (if all results are known and healthy)
      *    not healthy (if all results are known and at least one is unhealthy)
      */
    private[impl] val healthCheckStates: mutable.Map[InstanceKey, Map[HealthCheck, Option[Health]]] =
      mutable.Map.empty

    /**
      * This method derives the instance healthiness from a list of health check statuses
      * @param instanceHealthResults The map of health checks statuses per health check definition
      * @return The instance global healthiness
      */
    private def computeGlobalHealth(instanceHealthResults: Map[HealthCheck, Option[Health]]): Option[Boolean] = {
      val isHealthAlive = (health: Option[Health]) => health.fold(false)(_.alive)
      val isHealthUnknown = (health: Option[Health]) => health.isEmpty

      if (instanceHealthResults.values.forall(isHealthAlive))
        Some(true)
      else if (instanceHealthResults.values.exists(isHealthUnknown))
        Option.empty[Boolean]
      else
        Some(false)
    }

    /**
      * Add an health check definition for a given application so that the actor knows which health check
      * statuses are know and which are not for a given instance.
      * @param applicationKey The application key of the application for which the health check is defined
      * @param healthCheck The definition of the health check to add
      */
    def addHealthCheck(applicationKey: ApplicationKey, healthCheck: HealthCheck): Unit = {
      logger.debug(s"Add health check $healthCheck to instance appId:${applicationKey.appId} version:${applicationKey.version}")
      healthChecks.update(applicationKey, healthChecks.getOrElse(applicationKey, Set.empty) + healthCheck)
    }

    /**
      * Remove a health check definition for a given application.
      * This method also purges the statuses of health checks of all instances that ran it.
      * @param applicationKey The application key of the application for which the health check is removed
      * @param healthCheck The definition of the health check to remove
      */
    def removeHealthCheck(applicationKey: ApplicationKey, healthCheck: HealthCheck): Unit = {
      logger.debug(s"Remove health check $healthCheck from instance appId:${applicationKey.appId} version:${applicationKey.version}")
      purgeHealthCheckDefinition(applicationKey, healthCheck)
      healthCheckStates retain { (_, value) => value.exists(x => x._1 != healthCheck) }
    }

    /**
      * Generic purge method removing health check definitions or statuses from the actor containers
      * @param healthChecksContainers The container to purge
      * @param toPurge the health checks to purge from the container
      */
    private def purgeHealthChecks[K, A, V <: Traversable[A] with Subtractable[HealthCheck, V]](
      healthChecksContainers: mutable.Map[K, V], toPurge: Seq[(K, HealthCheck)]): Unit = {
      toPurge.foreach({
        case (key, healthCheck) =>
          healthChecksContainers.get(key) match {
            case Some(hcContainer) =>
              val newHcContainer = hcContainer - healthCheck

              if (newHcContainer.isEmpty)
                healthChecksContainers.remove(key)
              else
                healthChecksContainers.update(key, newHcContainer)
            case _ =>
          }
      })
    }

    /**
      * Purge one health check definition
      * @param applicationKey The application key of the application to purge the health check definition from
      * @param healthCheck The health check definition to purge
      */
    private def purgeHealthCheckDefinition(applicationKey: ApplicationKey, healthCheck: HealthCheck): Unit = {
      purgeHealthChecks[ApplicationKey, HealthCheck, Set[HealthCheck]](healthChecks, Seq(applicationKey -> healthCheck))
    }

    /**
      * Purge several health check statuses.
      * @param toPurge The list of health check statuses to purge.
      */
    private[impl] def purgeHealthChecksStatuses(toPurge: Seq[(InstanceKey, HealthCheck)]): Unit = {
      purgeHealthChecks[InstanceKey, (HealthCheck, Option[Health]), Map[HealthCheck, Option[Health]]](healthCheckStates, toPurge)
    }

    /**
      * Update the status of an health check of a given instance of an application. If the instance status
      * has changed, notifier callback is called.
      * @param appKey The application key for which the health check status is updated
      * @param healthCheck The health check for which the status is updated
      * @param healthState The new state of the health check
      * @param notifier The notifier callback called when the instance healthiness has changed.
      */
    def updateHealthCheckStatus(appKey: ApplicationKey, healthCheck: HealthCheck, healthState: Health,
      notifier: (Option[Boolean] => Unit)): Unit = {
      healthChecks.get(appKey) match {
        case Some(hcDefinitions) if hcDefinitions.contains(healthCheck) =>
          logger.debug(s"Status changed to $healthState for health check $healthCheck of " +
            s"instance appId:${appKey.appId} version:${appKey.version} instanceId:${healthState.instanceId}")

          val instanceKey = InstanceKey(appKey, healthState.instanceId)
          val currentInstanceHealthResults = healthCheckStates.getOrElse(instanceKey, {
            hcDefinitions.map(x => (x, Option.empty[Health])).toMap
          })

          val newInstanceHealthResults = currentInstanceHealthResults + (healthCheck -> Some(healthState))

          val currentInstanceGlobalHealth = computeGlobalHealth(currentInstanceHealthResults)
          val newInstanceGlobalHealth = computeGlobalHealth(newInstanceHealthResults)

          // only notifies on transitions between statuses
          if (currentInstanceGlobalHealth != newInstanceGlobalHealth)
            notifier(newInstanceGlobalHealth)

          healthCheckStates.update(instanceKey, newInstanceHealthResults)
        case _ =>
          logger.warn(s"Status of $healthCheck health check changed but it does not exist in inventory")
      }
    }
  }
}

/**
  * This actor aggregates the statuses of health checks at the application level
  * in order to maintain a global healthiness status for each instance.
  *
  * @param eventBus The eventStream to publish status changed events to
  */
class AppHealthCheckActor(eventBus: EventStream) extends Actor with StrictLogging {
  // A proxy class has been created in order to be tested in performance benchmarks.
  val proxy = new AppHealthCheckProxy

  private def notifyHealthChanged(applicationKey: ApplicationKey, instanceId: Instance.Id,
    healthiness: Option[Boolean]): Unit = {
    logger.debug(s"Instance global health status changed to healthiness=$healthiness " +
      s"for instance appId:$applicationKey instanceId:$instanceId")
    eventBus.publish(InstanceHealthChanged(
      instanceId, applicationKey.version, applicationKey.appId, healthiness))
  }

  override def receive: Receive = {
    case AddHealthCheck(appKey, healthCheck) =>
      proxy.addHealthCheck(appKey, healthCheck)

    case RemoveHealthCheck(appKey, healthCheck) =>
      proxy.removeHealthCheck(appKey, healthCheck)

    case PurgeHealthCheckStatuses(toPurge) =>
      proxy.purgeHealthChecksStatuses(toPurge)

    case HealthCheckStatusChanged(appKey, healthCheck, health) =>
      val notifier = (newHealthiness: Option[Boolean]) => {
        notifyHealthChanged(appKey, health.instanceId, newHealthiness)
      }
      proxy.updateHealthCheckStatus(appKey, healthCheck, health, notifier)
  }
}