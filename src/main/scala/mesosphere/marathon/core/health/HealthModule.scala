package mesosphere.marathon.core.health

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.ZookeeperConf
import mesosphere.marathon.core.health.impl.MarathonHealthCheckManager
import mesosphere.marathon.core.task.termination.TaskKillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.ReadOnlyAppRepository

/**
  * Exposes everything related to a task health, including the health check manager.
  */
class HealthModule(
    actorSystem: ActorSystem,
    killService: TaskKillService,
    eventBus: EventStream,
    taskTracker: InstanceTracker,
    appRepository: ReadOnlyAppRepository,
    zkConf: ZookeeperConf) {
  lazy val healthCheckManager = new MarathonHealthCheckManager(
    actorSystem,
    killService,
    eventBus,
    taskTracker,
    appRepository,
    zkConf)
}
