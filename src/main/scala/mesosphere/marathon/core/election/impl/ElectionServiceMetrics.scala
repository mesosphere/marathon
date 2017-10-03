package mesosphere.marathon
package core.election.impl

import java.util.concurrent.atomic.AtomicBoolean

import com.codahale.metrics.Gauge
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.metrics.Metrics.Timer

private[impl] trait ElectionServiceMetrics {
  protected val metrics: Metrics

  private[this] val leaderDurationMetric = "service.mesosphere.marathon.leaderDuration"
  protected val leaderHostPortMetric: Timer =
    metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "current-leader-host-port"))

  // [[areMetricsStarted]] is used to avoid calling [[stopMetrics]] if [[startMetrics]] was not invoked before,
  // or call [[stopMetrics]] twice (on both leader abdication and JVM shutdown).
  private[this] val areMetricsStarted = new AtomicBoolean(false)

  protected def startMetrics(): Unit =
    if (areMetricsStarted.compareAndSet(false, true)) {
      metrics.gauge(leaderDurationMetric, new Gauge[Long] {
        val startedAt: Long = System.currentTimeMillis()

        override def getValue: Long = {
          System.currentTimeMillis() - startedAt
        }
      })
    }

  protected def stopMetrics(): Unit =
    if (areMetricsStarted.compareAndSet(true, false)) {
      metrics.registry.remove(leaderDurationMetric)
    }
}
