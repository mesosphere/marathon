package mesosphere.marathon
package core.election

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.base.{ JvmExitsCrashStrategy, LifecycleState }
import mesosphere.marathon.core.election.impl.{ CuratorElectionService, PseudoElectionService }

class ElectionModule(
    config: MarathonConf,
    system: ActorSystem,
    eventStream: EventStream,
    hostPort: String,
    lifecycleState: LifecycleState) {

  lazy val service: ElectionService = if (config.highlyAvailable()) {
    config.leaderElectionBackend.get match {
      case Some("curator") =>
        new CuratorElectionService(
          config,
          hostPort,
          system,
          eventStream,
          lifecycleState,
          JvmExitsCrashStrategy
        )
      case backend: Option[String] =>
        throw new IllegalArgumentException(s"Leader election backend $backend not known!")
    }
  } else {
    new PseudoElectionService(
      hostPort,
      system,
      eventStream,
      lifecycleState,
      JvmExitsCrashStrategy
    )
  }
}
