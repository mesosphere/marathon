package mesosphere.marathon

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{ Named, Inject }

import akka.actor.ActorRef
import akka.event.{ EventStream, EventBus }
import com.twitter.common.zookeeper.Candidate
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.event.{ LocalLeadershipEvent, EventModule }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.metrics.Metrics.Timer

class MarathonLeaderInfo @Inject() (
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    @Named(EventModule.busName) eventStream: EventStream,
    metrics: MarathonLeaderInfoMetrics) extends LeaderInfo {

  /** Query whether we are the current leader. This should be cheap. */
  override def elected: Boolean = leader.get()

  override def currentLeaderHostPort(): Option[String] = metrics.getLeaderDataTimer {
    candidate.flatMap { c =>
      Option(c.getLeaderData.orNull()).map { data =>
        new String(data, "UTF-8")
      }
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

