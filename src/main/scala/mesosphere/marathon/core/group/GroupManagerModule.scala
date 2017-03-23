package mesosphere.marathon
package core.group

import javax.inject.Provider

import akka.event.EventStream
import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.storage.repository.GroupRepository

import scala.concurrent.{ Await, ExecutionContext }

/**
  * Provides a [[GroupManager]] implementation.
  */
class GroupManagerModule(
    config: GroupManagerConfig,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository,
    storage: StorageProvider)(implicit ctx: ExecutionContext, eventStream: EventStream) {

  val groupManager: GroupManager = {
    val groupManager = new GroupManagerImpl(config, Await.result(groupRepo.root(), config.zkTimeoutDuration), groupRepo, scheduler, storage)

    // We've already released metrics using these names, so we can't use the Metrics.* methods
    Kamon.metrics.gauge("service.mesosphere.marathon.app.count")(
      groupManager.rootGroup().transitiveApps.size.toLong
    )

    Kamon.metrics.gauge("service.mesosphere.marathon.group.count")(
      groupManager.rootGroup().transitiveGroupsById.size.toLong
    )

    val startedAt = System.currentTimeMillis()
    Kamon.metrics.gauge("service.mesosphere.marathon.uptime", Time.Milliseconds)(
      System.currentTimeMillis() - startedAt
    )

    groupManager
  }
}
