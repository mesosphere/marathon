package mesosphere.marathon
package core.group

import javax.inject.Provider

import akka.event.EventStream
import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.storage.repository.GroupRepository

import scala.concurrent.ExecutionContext

/**
  * Provides a [[GroupManager]] implementation.
  */
class GroupManagerModule(
    config: GroupManagerConfig,
    scheduler: Provider[DeploymentService],
    groupRepo: GroupRepository)(implicit ctx: ExecutionContext, eventStream: EventStream) {

  val groupManager: GroupManager = {
    val groupManager = new GroupManagerImpl(config, None, groupRepo, scheduler)

    // We've already released metrics using these names, so we can't use the Metrics.* methods
    Kamon.metrics.gauge("service.mesosphere.marathon.app.count")(
      groupManager.rootGroupOption().foldLeft(0L) { (_, group) =>
        group.transitiveApps.size.toLong
      }
    )

    Kamon.metrics.gauge("service.mesosphere.marathon.group.count")(
      groupManager.rootGroupOption().foldLeft(0L) { (_, group) =>
        group.transitiveGroupsById.size.toLong
      }
    )

    val startedAt = System.currentTimeMillis()
    Kamon.metrics.gauge("service.mesosphere.marathon.uptime", Time.Milliseconds)(
      System.currentTimeMillis() - startedAt
    )

    groupManager
  }
}
