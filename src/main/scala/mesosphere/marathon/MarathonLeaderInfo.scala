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
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class MarathonLeaderInfo @Inject() (
    @Named(ModuleNames.CANDIDATE) candidate: Option[Candidate],
    @Named(ModuleNames.LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
    @Named(EventModule.busName) eventStream: EventStream,
    metrics: MarathonLeaderInfoMetrics) extends LeaderInfo {

  private[this] val log = LoggerFactory.getLogger(getClass)

  /** Query whether we are the current leader. This should be cheap. */
  override def elected: Boolean = leader.get()

  override def currentLeaderHostPort(): Option[String] = metrics.getLeaderDataTimer {
    candidate.flatMap { c =>
      val maybeLeaderData: Option[Array[Byte]] = try {
        Option(c.getLeaderData.orNull())
      }
      catch {
        case NonFatal(e) =>
          log.error("error while getting current leader", e)
          None
      }
      maybeLeaderData.map { data =>
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

