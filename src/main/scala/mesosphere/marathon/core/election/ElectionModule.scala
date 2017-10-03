package mesosphere.marathon.core.election

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.{ CrashStrategy, ShutdownHooks }
import mesosphere.marathon.core.election.impl.{ CuratorElectionService, PseudoElectionService, TwitterCommonsElectionService }
import mesosphere.marathon.metrics.Metrics

class ElectionModule(
    config: MarathonConf,
    hostPort: String,
    system: ActorSystem,
    eventStream: EventStream,
    metrics: Metrics = new Metrics(new MetricRegistry),
    shutdownHooks: ShutdownHooks,
    crashStrategy: CrashStrategy) {

  lazy val service: ElectionService = if (config.highlyAvailable()) {
    config.leaderElectionBackend.get match {
      case Some("twitter_commons") =>
        new TwitterCommonsElectionService(
          config,
          hostPort,
          system,
          eventStream,
          metrics,
          shutdownHooks,
          crashStrategy
        )
      case Some("curator") =>
        new CuratorElectionService(
          config,
          hostPort,
          system,
          eventStream,
          metrics,
          shutdownHooks,
          crashStrategy
        )
      case backend: Option[String] =>
        throw new IllegalArgumentException(s"Leader election backend $backend not known!")
    }
  } else {
    new PseudoElectionService(
      hostPort,
      system,
      eventStream,
      metrics,
      shutdownHooks,
      crashStrategy
    )
  }
}
