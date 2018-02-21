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

/**
  * This actor aggregates the statuses of health checks at the application level
  * in order to maintain a global healthiness status for each instance.
  *
  * @param eventBus The eventStream to publish status changed events to
  */
class AppHealthCheckActor(eventBus: EventStream) extends Actor with StrictLogging {
  /**
    * Map of health check definitions of all applications
    */
  private var healthChecks: Map[ApplicationKey, Set[HealthCheck]] = Map.empty

  /**
    *  Map of results of all health checks for all applications.
    *  Results are optional, therefore the global health status of an instance
    *  is either:
    *    unknown (if some results are still missing)
    *    healthy (if all results are known and healthy)
    *    not healthy (if all results are known and at least one is unhealthy)
    */
  private var healthCheckStates: Map[InstanceKey, Map[HealthCheck, Option[Health]]] =
    Map.empty


  private def computeGlobalHealth(instanceHealthResults: Map[HealthCheck, Option[Health]]): Option[Boolean] = {
    val instanceHealthResultsSet = instanceHealthResults.values.toSet
    val healthy = instanceHealthResultsSet.forall(x => x.fold(false)(_.alive))
    val unhealthy = instanceHealthResultsSet.forall(_.nonEmpty) && !healthy
    (healthy, unhealthy) match {
      case (true, _) => Some(true)
      case (_, true) => Some(false)
      case _ => Option.empty[Boolean]
    }
  }

  private def notifyHealthChanged(applicationKey: ApplicationKey, instanceId: Instance.Id,
    healthiness: Option[Boolean]): Unit = {
    logger.debug(s"Instance global health status changed to healthiness=$healthiness " +
      s"for instance appId:$applicationKey instanceId:$instanceId")
    eventBus.publish(InstanceHealthChanged(
      instanceId, applicationKey.version, applicationKey.appId, healthiness))
  }

  private def healthCheckExists(applicationKey: ApplicationKey, healthCheck: HealthCheck): Boolean =
    healthChecks.contains(applicationKey) && healthChecks(applicationKey).contains(healthCheck)

  override def receive: Receive = {
    case AddHealthCheck(appKey, healthCheck) =>
      healthChecks = healthChecks +
        (appKey -> (healthChecks.getOrElse(appKey, Set.empty) + healthCheck))
      logger.debug(s"Add health check $healthCheck to instance appId:${appKey.appId} version:${appKey.version}")

    case RemoveHealthCheck(appKey, healthCheck) =>
      logger.debug(s"Remove health check $healthCheck from instance appId:${appKey.appId} version:${appKey.version}")

      healthChecks = healthChecks +
        (appKey -> (healthChecks.getOrElse(appKey, Set.empty) - healthCheck))

      healthCheckStates = healthCheckStates.map(kv => {
        val newHealthChecks = kv._2.filter(x => x._1 != healthCheck)
        (kv._1, newHealthChecks)
      })

    case HealthCheckStatusChanged(appKey, healthCheck, health) =>
      if (healthCheckExists(appKey, healthCheck)) {
        logger.debug(s"Status changed to $health for health check $healthCheck of " +
          s"instance appId:${appKey.appId} version:${appKey.version} instanceId:${health.instanceId}")

        val instanceKey = InstanceKey(appKey, health.instanceId)
        val currentInstanceHealthResults = healthCheckStates.getOrElse(instanceKey, {
          healthChecks.getOrElse(appKey, Set.empty).map(x => (x, Option.empty[Health])).toMap
        })

        val newInstanceHealthResults = currentInstanceHealthResults + (healthCheck -> Some(health))

        val currentInstanceGlobalHealth = computeGlobalHealth(currentInstanceHealthResults)
        val newInstanceGlobalHealth = computeGlobalHealth(newInstanceHealthResults)

        // only notifies on transitions between statuses
        if (currentInstanceGlobalHealth != newInstanceGlobalHealth)
          notifyHealthChanged(appKey, health.instanceId, newInstanceGlobalHealth)

        healthCheckStates = healthCheckStates +
          (instanceKey -> newInstanceHealthResults)
      }
  }
}

object AppHealthCheckActor {
  case class ApplicationKey(appId: PathId, version: Timestamp)
  case class InstanceKey(applicationKey: ApplicationKey, instanceId: Instance.Id)

  def props(eventBus: EventStream): Props = Props(new AppHealthCheckActor(eventBus))

  case class AddHealthCheck(appKey: ApplicationKey, healthCheck: HealthCheck)
  case class RemoveHealthCheck(appKey: ApplicationKey, healthCheck: HealthCheck)
  case class HealthCheckStatusChanged(
    appKey: ApplicationKey,
    healthCheck: HealthCheck, health: Health)
}