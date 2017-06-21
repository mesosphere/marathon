package mesosphere.marathon
package core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.marathon.core.base.LifecycleState
import org.slf4j.LoggerFactory

class PseudoElectionService(
  system: ActorSystem,
  eventStream: EventStream,
  hostPort: String,
  backoff: ExponentialBackoff,
  lifecycleState: LifecycleState) extends ElectionServiceBase(
  system, eventStream, backoff, lifecycleState
) {
  private val log = LoggerFactory.getLogger(getClass.getName)

  override def localHostPort: String = hostPort

  override def leaderHostPortImpl: Option[String] = if (isLeader) Some(hostPort) else None

  override def offerLeadershipImpl(): Unit = synchronized {
    log.info("Not using HA and therefore electing as leader by default")
    startLeadership(_ => stopLeadership())
  }
}
