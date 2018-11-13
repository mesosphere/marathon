package mesosphere.marathon
package raml

import java.time.ZoneId
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry

import scala.collection.JavaConverters._

trait MetricsConversion {
  lazy val zoneId = ZoneId.systemDefault()

  implicit val metricsRamlWriter: Writes[MetricRegistry, NewMetrics] = Writes { registry =>
    val counters = registry.getCounters().asScala.toMap.map {
      case (counterName, counter) =>
        counterName -> Counter(count = counter.getCount)
    }

    val gauges = registry.getGauges().asScala.toMap.map {
      case (gaugeName, gauge) =>
        val value = gauge.getValue match {
          case v: Double => if (v.isNaN) 0.0 else v
          case v: Float => if (v.isNaN) 0.0 else v.toDouble
          case v: Long => v.toDouble
          case v: Integer => v.toDouble
        }
        gaugeName -> Gauge(value = value)
    }

    val histograms = registry.getHistograms().asScala.toMap.map {
      case (histogramName, histogram) =>
        val snapshot = histogram.getSnapshot
        histogramName -> Histogram(
          count = histogram.getCount,
          min = snapshot.getMin.toDouble,
          mean = snapshot.getMean,
          max = snapshot.getMax.toDouble,
          p50 = snapshot.getMedian,
          p75 = snapshot.get75thPercentile,
          p95 = snapshot.get95thPercentile,
          p98 = snapshot.get98thPercentile,
          p99 = snapshot.get99thPercentile,
          p999 = snapshot.get999thPercentile,
          stddev = snapshot.getStdDev)
    }

    val rateFactor = TimeUnit.SECONDS.toSeconds(1)
    val meters = registry.getMeters().asScala.toMap.map {
      case (meterName, meter) =>
        meterName -> Meter(
          count = meter.getCount,
          m1_rate = meter.getOneMinuteRate * rateFactor,
          m5_rate = meter.getFiveMinuteRate * rateFactor,
          m15_rate = meter.getFifteenMinuteRate * rateFactor,
          mean_rate = meter.getMeanRate * rateFactor,
          units = "events/second")
    }

    val durationFactor = 1.0 / TimeUnit.SECONDS.toNanos(1)
    val timers = registry.getTimers().asScala.toMap.map {
      case (timerName, timer) =>
        val snapshot = timer.getSnapshot
        timerName -> Timer(
          count = timer.getCount,
          min = snapshot.getMin.toDouble * durationFactor,
          mean = snapshot.getMean * durationFactor,
          max = snapshot.getMax.toDouble * durationFactor,
          p50 = snapshot.getMedian * durationFactor,
          p75 = snapshot.get75thPercentile * durationFactor,
          p95 = snapshot.get95thPercentile * durationFactor,
          p98 = snapshot.get98thPercentile * durationFactor,
          p99 = snapshot.get99thPercentile * durationFactor,
          p999 = snapshot.get999thPercentile * durationFactor,
          stddev = snapshot.getStdDev * durationFactor,
          m1_rate = timer.getOneMinuteRate * rateFactor,
          m5_rate = timer.getFiveMinuteRate * rateFactor,
          m15_rate = timer.getFifteenMinuteRate * rateFactor,
          mean_rate = timer.getMeanRate * rateFactor,
          duration_units = "seconds",
          rate_units = "calls/second")
    }

    NewMetrics(
      version = "4.0.0",
      counters = counters,
      gauges = gauges,
      histograms = histograms,
      meters = meters,
      timers = timers)
  }
}

object MetricsConversion extends MetricsConversion
