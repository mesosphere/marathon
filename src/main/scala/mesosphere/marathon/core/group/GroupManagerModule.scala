package mesosphere.marathon
package core.group

import javax.inject.Provider
import akka.event.EventStream
import mesosphere.marathon.api.GroupApiService
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.current.UnitOfMeasurement
import mesosphere.marathon.plugin.auth.Authorizer
import mesosphere.marathon.storage.repository.GroupRepository

import scala.concurrent.ExecutionContext

/**
  * Provides a [[GroupManager]] implementation.
  */
class GroupManagerModule(
    metrics: Metrics,
    config: GroupManagerConfig,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository)(implicit ctx: ExecutionContext, eventStream: EventStream, authorizer: Authorizer) {

  val groupManager: GroupManager = {
    val groupManager = new GroupManagerImpl(metrics, config, None, groupRepo, scheduler)

    val startedAt = System.currentTimeMillis()
    metrics.closureGauge(
      "uptime",
      () => (System.currentTimeMillis() - startedAt).toDouble / 1000.0, unit = UnitOfMeasurement.Time)

    groupManager
  }

  val groupService: GroupApiService = {
    new GroupApiService(groupManager)
  }
}
