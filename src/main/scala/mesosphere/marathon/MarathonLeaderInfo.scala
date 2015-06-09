package mesosphere.marathon

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{ Named, Inject }

import com.twitter.common.zookeeper.Candidate
import mesosphere.marathon.api.LeaderInfo
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.Metrics.Timer

class MarathonLeaderInfo @Inject() (
    @Named(ModuleNames.NAMED_CANDIDATE) candidate: Option[Candidate],
    @Named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN) leader: AtomicBoolean,
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
}

class MarathonLeaderInfoMetrics @Inject() (metrics: Metrics) {
  val getLeaderDataTimer: Timer =
    metrics.timer(metrics.name("service", getClass, "current-leader-host-port"))
}

