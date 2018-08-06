package mesosphere.marathon
package raml

import java.time.{Instant, OffsetDateTime, ZoneId}
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{Time, Counter => KCounter, Histogram => KHistogram, UnitOfMeasurement => KUnitOfMeasurement}
import play.api.libs.json.{JsObject, Json}

import scala.collection.JavaConverters._

trait MetricsConversion {
  lazy val zoneId = ZoneId.systemDefault()

  implicit val kamonUnitOfMeasurementRamlWriter: Writes[KUnitOfMeasurement, KamonUnitOfMeasurement] = Writes {
    case t: Time =>
      if (t.label == "n") KamonTimeMeasurement("ns") else KamonTimeMeasurement(t.label)
    case general =>
      KamonGeneralMeasurement(name = general.name, label = general.label)
  }

  implicit val kamonMetricsRamlWriter: Writes[TickMetricSnapshot, KamonMetrics] = Writes { snapshot =>
    val metrics = snapshot.metrics.flatMap {
      case (entity, entitySnapshot) =>
        entitySnapshot.metrics.map {
          case (metricKey, metricSnapshot) =>
            val metricName = if (entity.category == metricKey.name) entity.name else s"${entity.name}.${metricKey.name}"
            metricSnapshot match {
              case histogram: KHistogram.Snapshot =>
                (entity.category, metricName) -> KamonHistogram(
                  count = histogram.numberOfMeasurements,
                  min = histogram.min,
                  max = histogram.max,
                  p50 = histogram.percentile(50.0),
                  p75 = histogram.percentile(75.0),
                  p98 = histogram.percentile(98.0),
                  p99 = histogram.percentile(99.0),
                  p999 = histogram.percentile(99.9),
                  mean = if (histogram.numberOfMeasurements != 0) histogram.sum.toFloat / histogram.numberOfMeasurements.toFloat else 0.0f,
                  tags = entity.tags,
                  unit = Raml.toRaml(metricKey.unitOfMeasurement)
                )
              case cs: KCounter.Snapshot =>
                (entity.category, metricName) ->
                  KamonCounter(count = cs.count, tags = entity.tags, unit = Raml.toRaml(metricKey.unitOfMeasurement))
            }
        }
    }.groupBy(_._1._1).map {
      case (category, allMetrics) =>
        category -> allMetrics.map { case ((_, name), entityMetrics) => name -> entityMetrics }
    }

    KamonMetrics(
      // the start zoneId could be different than the current system zone.
      start = OffsetDateTime.ofInstant(Instant.ofEpochMilli(snapshot.from.millis), zoneId),
      end = OffsetDateTime.ofInstant(Instant.ofEpochMilli(snapshot.to.millis), zoneId),
      counters = metrics.getOrElse("counter", Map.empty).collect { case (k, v: KamonCounter) => k -> v },
      gauges = metrics.getOrElse("gauge", Map.empty).collect { case (k, v: KamonHistogram) => k -> v },
      histograms = metrics.getOrElse("histogram", Map.empty).collect { case (k, v: KamonHistogram) => k -> v },
      `min-max-counters` = metrics.getOrElse("min-max-counter", Map.empty).collect { case (k, v: KamonHistogram) => k -> v },
      additionalProperties = JsObject(
        metrics.collect {
          case (name, metrics) if name != "counter" && name != "gauge" && name != "histogram" && name != "min-max-counter" =>
            name -> JsObject(metrics.collect {
              case (name, histogram: KamonHistogram) => name -> Json.toJson(histogram)
              case (name, counter: KamonCounter) => name -> Json.toJson(counter)
            })
        }
      )
    )
  }

  implicit val dropwizardMetricsRamlWriter: Writes[MetricRegistry, DropwizardMetrics] = Writes { registry =>
    val counters = registry.getCounters().asScala.toMap.map {
      case (counterName, counter) =>
        counterName -> DropwizardCounter(count = counter.getCount)
    }

    val gauges = registry.getGauges().asScala.toMap.map {
      case (gaugeName, gauge) =>
        val value = gauge.getValue match {
          case v: Double => if (v.isNaN) 0.0 else v
          case v: Float => if (v.isNaN) 0.0 else v.toDouble
          case v: Long => v.toDouble
          case v: Integer => v.toDouble
        }
        gaugeName -> DropwizardGauge(value = value)
    }

    val histograms = registry.getHistograms().asScala.toMap.map {
      case (histogramName, histogram) =>
        val snapshot = histogram.getSnapshot
        histogramName -> DropwizardHistogram(
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
        meterName -> DropwizardMeter(
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
        timerName -> DropwizardTimer(
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

    DropwizardMetrics(
      version = "4.0.0",
      counters = counters,
      gauges = gauges,
      histograms = histograms,
      meters = meters,
      timers = timers)
  }
}

object MetricsConversion extends MetricsConversion
