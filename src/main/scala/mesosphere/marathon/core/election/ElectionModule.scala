package mesosphere.marathon.core.election

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Features
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
    zk: ZooKeeperClient,
    electionCallbacks: Seq[ElectionCallback] = Seq.empty) {
  private lazy val backoff = new ExponentialBackoff(name = "offerLeadership")
  lazy val service: ElectionService = if (config.highlyAvailable()) {
    if (config.isFeatureSet(Features.TWITTER_COMMONS)) {
      new TwitterCommonsElectionService(
        config,
        system,
        eventStream,
        http,
        metrics,
        hostPort,
        zk,
        electionCallbacks,
        backoff
      )
    }
    else {
      new CuratorElectionService(
        config,
        system,
        eventStream,
        http,
        metrics,
        hostPort,
        zk,
        electionCallbacks,
        backoff
      )
    }
  }
  else {
    new PseudoElectionService(
      config,
      system,
      eventStream,
      metrics,
      hostPort,
      electionCallbacks,
      backoff
    )
  }
}
