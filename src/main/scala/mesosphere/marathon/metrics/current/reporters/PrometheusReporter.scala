package mesosphere.marathon
package metrics.current.reporters

import java.util
import java.util.concurrent.TimeUnit

import com.codahale.metrics.{Counter, Gauge, Histogram, Meter, Metered, MetricRegistry, Timer}

import scala.collection.JavaConverters._

object PrometheusReporter {
  def report(registry: MetricRegistry): String = {
    report(registry.getGauges, registry.getCounters, registry.getHistograms, registry.getMeters, registry.getTimers)
  }

  private def report(
    gauges: util.SortedMap[String, Gauge[_]],
    counters: util.SortedMap[String, Counter],
    histograms: util.SortedMap[String, Histogram],
    meters: util.SortedMap[String, Meter],
    timers: util.SortedMap[String, Timer]): String = {

    val buffer = new StringBuilder

    gauges.asScala.foreach { case (name, value) => reportGauge(buffer, sanitizeName(name), value) }
    counters.asScala.foreach { case (name, value) => reportCounter(buffer, sanitizeName(name), value) }
    histograms.asScala.foreach { case (name, value) => reportHistogram(buffer, sanitizeName(name), value) }
    meters.asScala.foreach { case (name, value) => reportMetered(buffer, sanitizeName(name), value) }
    timers.asScala.foreach { case (name, value) => reportTimer(buffer, sanitizeName(name), value) }

    buffer.toString()
  }

  private val forbiddenCharsRe = "[^a-zA-Z0-9_]".r
  private def sanitizeName(name: String): String = forbiddenCharsRe.replaceAllIn(name, "_")

  private def reportGauge(buffer: StringBuilder, name: String, gauge: Gauge[_]): Unit = {
    val value: Number = gauge.getValue match {
      case v: Double => if (v.isNaN) 0.0 else v
      case v: Float => if (v.isNaN) 0.0 else v.toDouble
      case v: Number => v
    }
    appendMetricType(buffer, name, "gauge")
    appendLine(buffer, s"$name $value")
    appendEmptyLine(buffer)
  }

  private def reportCounter(buffer: StringBuilder, name: String, counter: Counter): Unit = {
    appendMetricType(buffer, name, "counter")
    appendLine(buffer, s"$name ${counter.getCount}")
    appendEmptyLine(buffer)
  }

  private val rateFactor = TimeUnit.SECONDS.toSeconds(1)
  private val durationFactor = 1.0 / TimeUnit.SECONDS.toNanos(1)

  private def reportHistogram(buffer: StringBuilder, name: String, histogram: Histogram): Unit = {
    val count = histogram.getCount
    val snapshot = histogram.getSnapshot

    val min = snapshot.getMin
    val mean = snapshot.getMean
    val max = snapshot.getMax
    val p50 = snapshot.getMedian
    val p75 = snapshot.get75thPercentile()
    val p95 = snapshot.get95thPercentile()
    val p98 = snapshot.get98thPercentile()
    val p99 = snapshot.get99thPercentile()
    val p999 = snapshot.get999thPercentile()
    val stddev = snapshot.getStdDev

    appendMetricType(buffer, name, "summary")
    appendLine(buffer, """%s{quantile="0.5"} %f""".format(name, p50))
    appendLine(buffer, """%s{quantile="0.75"} %f""".format(name, p75))
    appendLine(buffer, """%s{quantile="0.95"} %f""".format(name, p95))
    appendLine(buffer, """%s{quantile="0.98"} %f""".format(name, p98))
    appendLine(buffer, """%s{quantile="0.99"} %f""".format(name, p99))
    appendLine(buffer, """%s{quantile="0.999"} %f""".format(name, p999))
    appendLine(buffer, s"${name}_count $count")

    appendMetricType(buffer, name + "_min", "gauge")
    appendLine(buffer, s"${name}_min $min")
    appendMetricType(buffer, name + "_mean", "gauge")
    appendLine(buffer, s"${name}_mean $mean")
    appendMetricType(buffer, name + "_max", "gauge")
    appendLine(buffer, s"${name}_max $max")
    appendMetricType(buffer, name + "_stddev", "gauge")
    appendLine(buffer, s"${name}_stddev $stddev")

    appendEmptyLine(buffer)
  }

  private def reportRates(buffer: StringBuilder, name: String, metered: Metered): Unit = {
    val meanRate = metered.getMeanRate * rateFactor
    val m1Rate = metered.getOneMinuteRate * rateFactor
    val m5Rate = metered.getFiveMinuteRate * rateFactor
    val m15Rate = metered.getFifteenMinuteRate * rateFactor

    appendMetricType(buffer, name + "_mean_rate", "gauge")
    appendLine(buffer, s"${name}_mean_rate $meanRate")
    appendMetricType(buffer, name + "_m1_rate", "gauge")
    appendLine(buffer, s"${name}_m1_rate $m1Rate")
    appendMetricType(buffer, name + "_m5_rate", "gauge")
    appendLine(buffer, s"${name}_m5_rate $m5Rate")
    appendMetricType(buffer, name + "_m15_rate", "gauge")
    appendLine(buffer, s"${name}_m15_rate $m15Rate")
  }

  private def reportMetered(buffer: StringBuilder, name: String, meter: Metered): Unit = {
    val count = meter.getCount

    appendMetricType(buffer, name + "_count", "gauge")
    appendLine(buffer, s"${name}_count $count")
    reportRates(buffer, name, meter)
    appendEmptyLine(buffer)
  }

  private def reportTimer(buffer: StringBuilder, name: String, timer: Timer): Unit = {
    val count = timer.getCount
    val snapshot = timer.getSnapshot

    val min = snapshot.getMin * durationFactor
    val mean = snapshot.getMean * durationFactor
    val max = snapshot.getMax * durationFactor
    val p50 = snapshot.getMedian * durationFactor
    val p75 = snapshot.get75thPercentile() * durationFactor
    val p95 = snapshot.get95thPercentile() * durationFactor
    val p98 = snapshot.get98thPercentile() * durationFactor
    val p99 = snapshot.get99thPercentile() * durationFactor
    val p999 = snapshot.get999thPercentile() * durationFactor
    val stddev = snapshot.getStdDev * durationFactor

    appendMetricType(buffer, name, "summary")
    appendLine(buffer, """%s{quantile="0.5"} %f""".format(name, p50))
    appendLine(buffer, """%s{quantile="0.75"} %f""".format(name, p75))
    appendLine(buffer, """%s{quantile="0.95"} %f""".format(name, p95))
    appendLine(buffer, """%s{quantile="0.98"} %f""".format(name, p98))
    appendLine(buffer, """%s{quantile="0.99"} %f""".format(name, p99))
    appendLine(buffer, """%s{quantile="0.999"} %f""".format(name, p999))
    appendLine(buffer, s"${name}_count $count")

    appendMetricType(buffer, name + "_min", "gauge")
    appendLine(buffer, s"${name}_min $min")
    appendMetricType(buffer, name + "_mean", "gauge")
    appendLine(buffer, s"${name}_mean $mean")
    appendMetricType(buffer, name + "_max", "gauge")
    appendLine(buffer, s"${name}_max $max")
    appendMetricType(buffer, name + "_stddev", "gauge")
    appendLine(buffer, s"${name}_stddev $stddev")

    reportRates(buffer, name, timer)

    appendEmptyLine(buffer)
  }

  private def appendMetricType(buffer: StringBuilder, name: String, metricType: String): Unit = {
    appendLine(buffer, s"# TYPE $name $metricType")
  }

  private def appendLine(buffer: StringBuilder, line: String): Unit = {
    buffer.append(line)
    buffer.append('\n')
  }

  private def appendEmptyLine(buffer: StringBuilder): Unit = buffer.append('\n')
}
