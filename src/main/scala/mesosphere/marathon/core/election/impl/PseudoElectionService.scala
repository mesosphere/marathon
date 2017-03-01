package mesosphere.marathon
package core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base.ShutdownHooks
import mesosphere.marathon.metrics.Metrics
import org.slf4j.LoggerFactory

class PseudoElectionService(
  system: ActorSystem,
  eventStream: EventStream,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  backoff: ExponentialBackoff,
  shutdownHooks: ShutdownHooks) extends ElectionServiceBase(
  system, eventStream, metrics, backoff, shutdownHooks
) {
  private val log = LoggerFactory.getLogger(getClass.getName)

  override def leaderHostPortImpl: Option[String] = if (isLeader) Some(hostPort) else None

  override def offerLeadershipImpl(): Unit = synchronized {
    log.info("Not using HA and therefore electing as leader by default")
    startLeadership(_ => stopLeadership())
  }
}
