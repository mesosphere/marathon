package mesosphere.marathon.core.health

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.health.impl.MarathonHealthCheckManager
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, ZookeeperConf }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.AppRepository

/**
  * Exposes everything related to a task health, including the health check manager.
  */
class HealthModule(
    actorSystem: ActorSystem,
    driverHolder: MarathonSchedulerDriverHolder,
    eventBus: EventStream,
    taskTracker: TaskTracker,
    appRepository: AppRepository,
    zkConf: ZookeeperConf) {
  lazy val healthCheckManager = new MarathonHealthCheckManager(
    actorSystem,
    driverHolder,
    eventBus,
    taskTracker,
    appRepository,
    zkConf)
}
