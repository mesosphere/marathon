package mesosphere.marathon
package core.election.impl

import java.util.concurrent.atomic.AtomicBoolean

import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, Timer }

private[impl] trait ElectionServiceMetrics {
  private[this] val leaderDurationMetric = "service.mesosphere.marathon.leaderDuration"
  protected val leaderHostPortMetric: Timer = Metrics.timer(ServiceMetric, getClass, "current-leader-host-port")

  // [[areMetricsStarted]] is used to avoid calling [[stopMetrics]] if [[startMetrics]] was not invoked before,
  // or call [[stopMetrics]] twice (on both leader abdication and JVM shutdown).
  private[this] val areMetricsStarted = new AtomicBoolean(false)

  protected def startMetrics(): Unit =
    if (areMetricsStarted.compareAndSet(false, true)) {
      val startedAt = System.currentTimeMillis()
      Kamon.metrics.gauge(leaderDurationMetric, Time.Milliseconds)(System.currentTimeMillis() - startedAt)
    }

  protected def stopMetrics(): Unit =
    if (areMetricsStarted.compareAndSet(true, false)) {
      Kamon.metrics.removeGauge(leaderDurationMetric)
    }
}
