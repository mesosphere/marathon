package mesosphere.marathon

import javax.inject.{ Named, Inject }

import akka.actor.ActorRef
import akka.event.EventStream
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.event.{ LocalLeadershipEvent, EventModule }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.metrics.Metrics.Timer
import org.slf4j.LoggerFactory

class MarathonLeaderInfo @Inject() (
    electionService: ElectionService,
    @Named(EventModule.busName) eventStream: EventStream,
    metrics: MarathonLeaderInfoMetrics) extends LeaderInfo {

  private[this] val log = LoggerFactory.getLogger(getClass)

  /** Query whether we are the current leader. This should be cheap. */
  override def elected: Boolean = electionService.isLeader

  override def currentLeaderHostPort(): Option[String] = metrics.getLeaderDataTimer {
    try {
      electionService.leaderHostPort
    }
    catch {
      case e: java.lang.Exception => None
    }
  }

  /**
    * Subscribe to leadership change events.
    *
    * The given actorRef will initally get the current state via the appropriate
    * [[mesosphere.marathon.event.LocalLeadershipEvent]] message and will
    * be informed of changes after that.
    */
  override def subscribe(self: ActorRef): Unit = {
    eventStream.subscribe(self, classOf[LocalLeadershipEvent])
    val currentState = if (elected) LocalLeadershipEvent.ElectedAsLeader else LocalLeadershipEvent.Standby
    self ! currentState
  }

  /** Unsubscribe to any leadership change events to this actor ref. */
  override def unsubscribe(self: ActorRef): Unit = {
    eventStream.unsubscribe(self, classOf[LocalLeadershipEvent])
  }
}

class MarathonLeaderInfoMetrics @Inject() (metrics: Metrics) {
  val getLeaderDataTimer: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "current-leader-host-port"))
}

