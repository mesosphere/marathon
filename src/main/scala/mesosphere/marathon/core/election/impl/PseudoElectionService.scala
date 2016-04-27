package mesosphere.marathon.core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.election.ElectionCallback
import mesosphere.marathon.metrics.Metrics
import org.slf4j.LoggerFactory

class PseudoElectionService(
  config: MarathonConf,
  system: ActorSystem,
  eventStream: EventStream,
  metrics: Metrics = new Metrics(new MetricRegistry),
  hostPort: String,
  electionCallbacks: Seq[ElectionCallback] = Seq.empty,
  backoff: ExponentialBackoff) extends ElectionServiceBase(
  config, system, eventStream, metrics, electionCallbacks, backoff
) {
  private val log = LoggerFactory.getLogger(getClass.getName)

  def leaderHostPort: Option[String] = if (isLeader) Some(hostPort) else None

  override def offerLeadershipImpl(): Unit = synchronized {
    log.info("Not using HA and therefore electing as leader by default")
    startLeadership(_ => stopLeadership())
  }
}
