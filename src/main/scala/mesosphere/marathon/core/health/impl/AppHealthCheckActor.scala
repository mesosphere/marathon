package mesosphere.marathon
package core.health.impl

import akka.actor.{ Actor, Props }
import akka.event.EventStream
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.InstanceHealthChanged
import mesosphere.marathon.core.health.impl.AppHealthCheckActor._
import mesosphere.marathon.core.health.{ Health, HealthCheck }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.collection.mutable

object AppHealthCheckActor {
  case class ApplicationKey(appId: PathId, version: Timestamp)
  case class InstanceKey(applicationKey: ApplicationKey, instanceId: Instance.Id)

  def props(eventBus: EventStream): Props = Props(new AppHealthCheckActor(eventBus))

  case class AddHealthCheck(appKey: ApplicationKey, healthCheck: HealthCheck)
  case class RemoveHealthCheck(appKey: ApplicationKey, healthCheck: HealthCheck)

  case class PurgeHealthCheckStatuses(hc: Seq[(InstanceKey, HealthCheck)])
  case class HealthCheckStatusChanged(
      appKey: ApplicationKey,
      healthCheck: HealthCheck, health: Health)

  trait InstanceHealthChangedNotifier {
    def notify(applicationKey: ApplicationKey, instanceId: Instance.Id,
      healthiness: Option[Boolean]): Unit
  }

  class AppHealthCheckProxy extends StrictLogging {
    /**
      * Map of health check definitions of all applications
      */
    var healthChecks: mutable.Map[ApplicationKey, Set[HealthCheck]] = mutable.Map.empty

    /**
      *  Map of results of all health checks for all applications.
      *  Results are optional, therefore the global health status of an instance
      *  is either:
      *    unknown (if some results are still missing)
      *    healthy (if all results are known and healthy)
      *    not healthy (if all results are known and at least one is unhealthy)
      */
    var healthCheckStates: mutable.Map[InstanceKey, Map[HealthCheck, Option[Health]]] =
      mutable.Map.empty

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

    def addHealthCheck(applicationKey: ApplicationKey, healthCheck: HealthCheck): Unit = {
      healthChecks.update(applicationKey, healthChecks.getOrElse(applicationKey, Set.empty) + healthCheck)
      logger.debug(s"Add health check $healthCheck to instance appId:${applicationKey.appId} version:${applicationKey.version}")
    }

    def removeHealthCheck(applicationKey: ApplicationKey, healthCheck: HealthCheck): Unit = {
      logger.debug(s"Remove health check $healthCheck from instance appId:${applicationKey.appId} version:${applicationKey.version}")

      val healthChecksAfterRemoval = healthChecks.getOrElse(applicationKey, Set.empty) - healthCheck
      if (healthChecksAfterRemoval.isEmpty)
        healthChecks.remove(applicationKey)
      else
        healthChecks.update(applicationKey, healthChecksAfterRemoval)

      healthCheckStates = healthCheckStates.map(kv => {
        val newHealthChecks = kv._2.filter(x => x._1 != healthCheck)
        (kv._1, newHealthChecks)
      }).filter(kv => kv._2.nonEmpty)
    }

    def purgeHealthCheckStatuses(toPurge: Seq[(InstanceKey, HealthCheck)]): Unit = {
      toPurge.foreach(hc => {
        val instanceKey = hc._1
        val healthCheck = hc._2
        val newInstanceHealthChecks = healthCheckStates.getOrElse(instanceKey, Map.empty) - healthCheck
        if (newInstanceHealthChecks.isEmpty)
          healthCheckStates.remove(instanceKey)
        else
          healthCheckStates.update(instanceKey, newInstanceHealthChecks)
      })
    }

    def updateHealthCheckStatus(appKey: ApplicationKey, healthCheck: HealthCheck, health: Health,
      notifier: (Option[Boolean] => Unit)): Unit = {
      val hcDefinitions = healthChecks.getOrElse(appKey, Set.empty)
      if (hcDefinitions.contains(healthCheck)) {
        logger.debug(s"Status changed to $health for health check $healthCheck of " +
          s"instance appId:${appKey.appId} version:${appKey.version} instanceId:${health.instanceId}")

        val instanceKey = InstanceKey(appKey, health.instanceId)
        val currentInstanceHealthResults = healthCheckStates.getOrElse(instanceKey, {
          hcDefinitions.map(x => (x, Option.empty[Health])).toMap
        })

        val newInstanceHealthResults = currentInstanceHealthResults + (healthCheck -> Some(health))

        val currentInstanceGlobalHealth = computeGlobalHealth(currentInstanceHealthResults)
        val newInstanceGlobalHealth = computeGlobalHealth(newInstanceHealthResults)

        // only notifies on transitions between statuses
        if (currentInstanceGlobalHealth != newInstanceGlobalHealth)
          notifier(newInstanceGlobalHealth)

        healthCheckStates.update(instanceKey, newInstanceHealthResults)
      } else {
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
      proxy.purgeHealthCheckStatuses(toPurge)

    case HealthCheckStatusChanged(appKey, healthCheck, health) =>
      val notifier = (healthiness: Option[Boolean]) => {
        notifyHealthChanged(appKey, health.instanceId, healthiness)
      }
      proxy.updateHealthCheckStatus(appKey, healthCheck, health, notifier)
  }
}