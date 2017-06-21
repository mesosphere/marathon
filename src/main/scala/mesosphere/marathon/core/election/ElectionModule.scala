package mesosphere.marathon
package core.election

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.core.election.impl.{ CuratorElectionService, ExponentialBackoff, PseudoElectionService }

class ElectionModule(
    config: MarathonConf,
    system: ActorSystem,
    eventStream: EventStream,
    hostPort: String,
    lifecycleState: LifecycleState) {

  private lazy val backoff = new ExponentialBackoff(name = "offerLeadership")
  lazy val service: ElectionService = if (config.highlyAvailable()) {
    config.leaderElectionBackend.get match {
      case Some("curator") =>
        new CuratorElectionService(
          config,
          system,
          eventStream,
          hostPort,
          backoff,
          lifecycleState
        )
      case backend: Option[String] =>
        throw new IllegalArgumentException(s"Leader election backend $backend not known!")
    }
  } else {
    new PseudoElectionService(
      system,
      eventStream,
      hostPort,
      backoff,
      lifecycleState
    )
  }
}
