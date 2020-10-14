package mesosphere.marathon
package core.health

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.stream.ActorMaterializer
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.impl.MarathonHealthCheckManager
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker

/**
  * Exposes everything related to a task health, including the health check manager.
  */
class HealthModule(
    actorSystem: ActorSystem,
    killService: KillService,
    eventBus: EventStream,
    instanceTracker: InstanceTracker,
    groupManager: GroupManager,
    conf: MarathonConf
)(implicit mat: ActorMaterializer) {
  lazy val healthCheckManager = new MarathonHealthCheckManager(actorSystem, killService, eventBus, instanceTracker, groupManager, conf)
}
