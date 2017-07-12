package mesosphere.marathon
package raml

import java.time.{ Instant, OffsetDateTime, ZoneId }

import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{ Time, Counter => KCounter, Histogram => KHistogram, UnitOfMeasurement => KUnitOfMeasurement }
import play.api.libs.json.{ JsObject, Json }

trait MetricsConversion {
  lazy val zoneId = ZoneId.systemDefault()
  implicit val unitOfMeasurementRamlWriter: Writes[KUnitOfMeasurement, UnitOfMeasurement] = Writes {
    case t: Time =>
      if (t.label == "n") TimeMeasurement("ns") else TimeMeasurement(t.label)
    case general =>
      GeneralMeasurement(name = general.name, label = general.label)
  }

  implicit val metricsRamlWriter: Writes[TickMetricSnapshot, Metrics] = Writes { snapshot =>
    val metrics = snapshot.metrics.flatMap {
      case (entity, entitySnapshot) =>
        entitySnapshot.metrics.map {
          case (metricKey, metricSnapshot) =>
            val metricName = if (entity.category == metricKey.name) entity.name else s"${entity.name}.${metricKey.name}"
            metricSnapshot match {
              case histogram: KHistogram.Snapshot =>
                (entity.category, metricName) -> Histogram(
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
                  Counter(count = cs.count, tags = entity.tags, unit = Raml.toRaml(metricKey.unitOfMeasurement))
            }
        }
    }.groupBy(_._1._1).map {
      case (category, allMetrics) =>
        category -> allMetrics.map { case ((_, name), entityMetrics) => name -> entityMetrics }
    }

    Metrics(
      // the start zoneId could be different than the current system zone.
      start = OffsetDateTime.ofInstant(Instant.ofEpochMilli(snapshot.from.millis), zoneId),
      end = OffsetDateTime.ofInstant(Instant.ofEpochMilli(snapshot.from.millis), ZoneId.systemDefault()),
      counters = metrics.getOrElse("counter", Map.empty).collect { case (k, v: Counter) => k -> v },
      gauges = metrics.getOrElse("gauge", Map.empty).collect { case (k, v: Histogram) => k -> v },
      histograms = metrics.getOrElse("histogram", Map.empty).collect { case (k, v: Histogram) => k -> v },
      `min-max-counters` = metrics.getOrElse("min-max-counter", Map.empty).collect { case (k, v: Histogram) => k -> v },
      additionalProperties = JsObject(
        metrics.collect {
          case (name, metrics) if name != "counter" && name != "gauge" && name != "histogram" && name != "min-max-counter" =>
            name -> JsObject(metrics.collect {
              case (name, histogram: Histogram) => name -> Json.toJson(histogram)
              case (name, counter: Counter) => name -> Json.toJson(counter)
            })
        }
      )
    )
  }
}

object MetricsConversion extends MetricsConversion
