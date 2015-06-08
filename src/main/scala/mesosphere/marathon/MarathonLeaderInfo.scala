package mesosphere.marathon

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{ Named, Inject }

import com.codahale.metrics.MetricRegistry
import com.twitter.common.zookeeper.Candidate
import mesosphere.marathon.api.LeaderInfo
import mesosphere.util.TimerUtils
import mesosphere.util.TimerUtils.ScalaTimer

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

class MarathonLeaderInfoMetrics @Inject() (metrics: MetricRegistry) {
  val getLeaderDataTimer: ScalaTimer =
    TimerUtils.timer(metrics, getClass, "current-leader-host-port")
}

