package mesosphere.marathon.core.election

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.core.election.impl.{
  ExponentialBackoff,
  CuratorElectionService,
  TwitterCommonsElectionService,
  PseudoElectionService
}
import mesosphere.marathon.metrics.Metrics

class ElectionModule(
    config: MarathonConf,
    system: ActorSystem,
    eventStream: EventStream,
    http: HttpConf,
    metrics: Metrics = new Metrics(new MetricRegistry),
    hostPort: String,
    shutdownHooks: ShutdownHooks) {
  private lazy val backoff = new ExponentialBackoff(name = "offerLeadership")
  lazy val service: ElectionService = if (config.highlyAvailable()) {
    config.leaderElectionBackend.get match {
      case Some("twitter_commons") =>
        new TwitterCommonsElectionService(
          config,
          system,
          eventStream,
          http,
          metrics,
          hostPort,
          backoff,
          shutdownHooks
        )
      case Some("curator") =>
        new CuratorElectionService(
          config,
          system,
          eventStream,
          http,
          metrics,
          hostPort,
          backoff,
          shutdownHooks
        )
      case backend: Option[String] =>
        throw new IllegalArgumentException(s"Leader election backend $backend not known!")
    }
  } else {
    new PseudoElectionService(
      config,
      system,
      eventStream,
      metrics,
      hostPort,
      backoff,
      shutdownHooks
    )
  }
}
