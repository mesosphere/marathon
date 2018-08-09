package mesosphere.marathon
package metrics.current.reporters

import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse, MediaTypes, StatusCodes}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.codahale.metrics.{Counter, Gauge, Histogram, Metered, MetricRegistry, Snapshot, Timer}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.metrics.MetricsConf

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

class DataDogAPIReporter(metricsConf: MetricsConf, registry: MetricRegistry) extends Actor with StrictLogging {
  private val apiKey = metricsConf.metricsDataDogApiKey()
  private val apiUrl = s"https://app.datadoghq.com/api/v1/series?api_key=$apiKey"
  private val transmissionIntervalMs = metricsConf.metricsDataDogTransmissionIntervalMs()
  private val transmissionIntervalS = transmissionIntervalMs / 1000
  private val http = Http(context.system)
  private val host = InetAddress.getLocalHost.getHostName

  private case object Tick

  import akka.pattern.pipe
  import scala.concurrent.ExecutionContext.Implicits.global

  private implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  override def preStart(): Unit = {
    self ! Tick
  }

  override def receive: Receive = {
    case Tick =>
      report()
    case HttpResponse(code, _, entity, _) =>
      if (code != StatusCodes.Accepted) {
        entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
          logger.info(s"Got an unexpected response from DataDog: code=$code, body=$body")
        }
      } else {
        entity.discardBytes()
      }
      context.system.scheduler.scheduleOnce(
        FiniteDuration(transmissionIntervalMs, TimeUnit.MILLISECONDS), self, Tick)
  }

  private def report(): Unit = {
    val buffer = new StringBuilder
    val timestamp = Clock.systemUTC().instant().getEpochSecond

    registry.getGauges.asScala.foreach { case (name, gauge) => reportGauge(buffer, sanitizeName(name), gauge, timestamp) }
    registry.getCounters.asScala.foreach { case (name, counter) => reportCounter(buffer, sanitizeName(name), counter, timestamp) }
    registry.getHistograms.asScala.foreach { case (name, histogram) => reportHistogram(buffer, sanitizeName(name), histogram, timestamp) }
    registry.getMeters.asScala.foreach { case (name, value) => reportMetered(buffer, sanitizeName(name), value, timestamp) }
    registry.getTimers.asScala.foreach { case (name, value) => reportTimer(buffer, sanitizeName(name), value, timestamp) }

    val data = buffer
      .insert(0, "{\"series\":[")
      .append("]}")
      .toString

    val body = HttpEntity(MediaTypes.`application/json`, data)
    http.singleRequest(HttpRequest(method = HttpMethods.POST, uri = apiUrl, entity = body)).pipeTo(self)
  }

  private val forbiddenCharsRe = "[^a-zA-Z0-9_.]".r
  private def sanitizeName(name: String): String = forbiddenCharsRe.replaceAllIn(name, "_")

  private val rateFactor = TimeUnit.SECONDS.toSeconds(1)
  private val durationFactor = 1.0 / TimeUnit.SECONDS.toNanos(1)

  private def reportGauge(buffer: StringBuilder, name: String, gauge: Gauge[_], timestamp: Long): Unit = {
    val value: Number = gauge.getValue match {
      case v: Double => if (v.isNaN) 0.0 else v
      case v: Float => if (v.isNaN) 0.0 else v.toDouble
      case v: Number => v
    }

    reportMetric(buffer, name, value.toString, timestamp, "gauge")
  }

  private def reportCounter(buffer: StringBuilder, name: String, counter: Counter, timestamp: Long): Unit =
    reportMetric(buffer, name, counter.getCount.toString, timestamp, "count")

  private val histogramSnapshotSuffixes =
    Seq("min", "average", "median", "75percentile", "95percentile", "98percentile",
      "99percentile", "999percentile", "max", "stddev")
  private def reportSnapshot(buffer: StringBuilder, name: String, snapshot: Snapshot, timestamp: Long,
    scaleMetrics: Boolean): Unit = {
    val values = Seq(
      snapshot.getMin.toDouble,
      snapshot.getMean,
      snapshot.getMedian,
      snapshot.get75thPercentile(),
      snapshot.get95thPercentile(),
      snapshot.get98thPercentile(),
      snapshot.get99thPercentile(),
      snapshot.get999thPercentile(),
      snapshot.getMax.toDouble,
      snapshot.getStdDev)
    val scaledValues = if (scaleMetrics) values.map(_ * durationFactor) else values

    histogramSnapshotSuffixes.zip(scaledValues).foreach {
      case (suffix, value) => reportMetric(buffer, s"$name.$suffix", value.toString, timestamp, "gauge")
    }
  }

  private def reportHistogram(buffer: StringBuilder, name: String, histogram: Histogram, timestamp: Long): Unit = {
    val count = histogram.getCount.toDouble
    reportMetric(buffer, s"$name.count", count.toString, timestamp, "gauge")
    reportSnapshot(buffer, name, histogram.getSnapshot, timestamp, false)
  }

  private val meteredSuffixes = Seq("count", "mean_rate", "m1_rate", "m5_rate", "m15_rate")
  private def reportMetered(buffer: StringBuilder, name: String, meter: Metered, timestamp: Long): Unit = {
    val values = Seq(
      meter.getCount.toDouble,
      meter.getMeanRate * rateFactor,
      meter.getOneMinuteRate * rateFactor,
      meter.getFiveMinuteRate * rateFactor,
      meter.getFifteenMinuteRate * rateFactor)
    meteredSuffixes.zip(values).foreach {
      case (suffix, value) => reportMetric(buffer, s"$name.$suffix", value.toString, timestamp, "gauge")
    }
  }

  private def reportTimer(buffer: StringBuilder, name: String, timer: Timer, timestamp: Long): Unit = {
    val count = timer.getCount.toDouble
    reportMetric(buffer, s"$name.count", count.toString, timestamp, "gauge")
    val snapshot = timer.getSnapshot
    reportSnapshot(buffer, name, snapshot, timestamp, true)
    reportMetered(buffer, name, timer, timestamp)
  }

  private def reportMetric(buffer: StringBuilder, name: String, value: String, timestamp: Long,
    metricType: String): Unit = {
    if (buffer.length() > 0) buffer.append(',')
    buffer.append(s"""{"metric":"$name","interval":$transmissionIntervalS,"points":[[$timestamp,$value]],"type":"$metricType",host:"$host"}""")
  }
}

object DataDogAPIReporter {
  def props(metricsConf: MetricsConf, registry: MetricRegistry): Props = {
    Props(new DataDogAPIReporter(metricsConf, registry))
  }
}
