package mesosphere.marathon.core.health

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.health.impl.MarathonHealthCheckManager
import mesosphere.marathon.core.storage.repository.ReadOnlyAppRepository
import mesosphere.marathon.core.task.termination.TaskKillService
import mesosphere.marathon.ZookeeperConf
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.termination.TaskKillService
import mesosphere.marathon.ZookeeperConf

/**
  * Exposes everything related to a task health, including the health check manager.
  */
class HealthModule(
    actorSystem: ActorSystem,
    killService: TaskKillService,
    eventBus: EventStream,
    taskTracker: TaskTracker,
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
