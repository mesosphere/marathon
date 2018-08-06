package mesosphere.marathon
package metrics.current.reporters

import java.net.InetSocketAddress
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.io.{IO, Udp}
import akka.util.ByteString
import com.codahale.metrics.{Counter, Gauge, Histogram, Meter, Metered, MetricRegistry, Snapshot, Timer}
import mesosphere.marathon.metrics.MetricsConf

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

class StatsDReporter(metricsConf: MetricsConf, registry: MetricRegistry) extends Actor {
  private val remote: InetSocketAddress = {
    val host = metricsConf.metricsStatsDHost()
    val port = metricsConf.metricsStatsDPort()
    new InetSocketAddress(host, port)
  }
  private val transmissionIntervalMs: Long = metricsConf.metricsStatsDTransmissionIntervalMs()

  private case object Tick

  import context.system
  import scala.concurrent.ExecutionContext.Implicits.global

  IO(Udp) ! Udp.SimpleSender

  def receive: PartialFunction[Any, Unit] = {
    case Udp.SimpleSenderReady =>
      self ! Tick
      context.become(ready(sender()))
  }

  def ready(socket: ActorRef): Receive = {
    case Tick =>
      report(socket)
      context.system.scheduler.scheduleOnce(FiniteDuration(transmissionIntervalMs, TimeUnit.MILLISECONDS), self, Tick)
  }

  private def report(socket: ActorRef): Unit = {
    report(
      socket,
      registry.getGauges, registry.getCounters, registry.getHistograms, registry.getMeters, registry.getTimers)
  }

  private def report(
    socket: ActorRef,
    gauges: util.SortedMap[String, Gauge[_]],
    counters: util.SortedMap[String, Counter],
    histograms: util.SortedMap[String, Histogram],
    meters: util.SortedMap[String, Meter],
    timers: util.SortedMap[String, Timer]): Unit = {

    gauges.asScala.foreach { case (name, value) => reportGauge(socket, name, value) }
    counters.asScala.foreach { case (name, value) => reportCounter(socket, name, value) }
    histograms.asScala.foreach { case (name, value) => reportHistogram(socket, name, value) }
    meters.asScala.foreach { case (name, value) => reportMetered(socket, name, value) }
    timers.asScala.foreach { case (name, value) => reportTimer(socket, name, value) }

    flush(socket)
  }

  private val rateFactor = TimeUnit.SECONDS.toSeconds(1)
  private val durationFactor = 1.0 / TimeUnit.SECONDS.toNanos(1)

  private def reportGauge(socket: ActorRef, name: String, gauge: Gauge[_]): Unit = {
    val value: Number = gauge.getValue match {
      case v: Double => if (v.isNaN) 0.0 else v
      case v: Float => if (v.isNaN) 0.0 else v.toDouble
      case v: Number => v
    }

    maybeSendAndAppend(socket, s"$name:$value|g\n")
  }

  private def reportCounter(socket: ActorRef, name: String, counter: Counter): Unit =
    maybeSendAndAppend(socket, s"$name:${counter.getCount}|c\n")

  private val histogramSnapshotSuffixes =
    Seq("min", "mean", "p50", "p75", "p95", "p98", "p99", "p999", "max", "stddev")
  private def reportSnapshot(socket: ActorRef, name: String, snapshot: Snapshot,
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
      case (suffix, value) => maybeSendAndAppend(socket, s"$name.$suffix:$value|g\n")
    }
  }

  private def reportHistogram(socket: ActorRef, name: String, histogram: Histogram): Unit = {
    val count = histogram.getCount.toDouble
    maybeSendAndAppend(socket, s"$name.count:$count|g\n")
    reportSnapshot(socket, name, histogram.getSnapshot, false)
  }

  private val meteredSuffixes = Seq("count", "mean_rate", "m1_rate", "m5_rate", "m15_rate")
  private def reportMetered(socket: ActorRef, name: String, meter: Metered): Unit = {
    val values = Seq(
      meter.getCount.toDouble,
      meter.getMeanRate * rateFactor,
      meter.getOneMinuteRate * rateFactor,
      meter.getFiveMinuteRate * rateFactor,
      meter.getFifteenMinuteRate * rateFactor)
    meteredSuffixes.zip(values).foreach {
      case (suffix, value) => maybeSendAndAppend(socket, s"$name.$suffix:$value|g\n")
    }
  }

  private def reportTimer(socket: ActorRef, name: String, timer: Timer): Unit = {
    val count = timer.getCount.toDouble
    maybeSendAndAppend(socket, s"$name.count:$count|g\n")
    val snapshot = timer.getSnapshot
    reportSnapshot(socket, name, snapshot, true)
    reportMetered(socket, name, timer)
  }

  val maxPayloadSize = 508 // the maximum safe UDP payload size
  val buffer = new StringBuilder

  private def maybeSendAndAppend(socket: ActorRef, measurement: String): Unit = {
    require(measurement.length <= maxPayloadSize, "measurement length is too large")

    if (buffer.length + measurement.length > maxPayloadSize)
      flush(socket)
    buffer.append(measurement)
  }

  private def flush(socket: ActorRef): Unit = {
    if (buffer.nonEmpty) {
      socket ! Udp.Send(ByteString(buffer.toString()), remote)
      buffer.clear()
    }
  }
}

object StatsDReporter {
  def props(metricsConf: MetricsConf, registry: MetricRegistry): Props = {
    Props(new StatsDReporter(metricsConf, registry))
  }

}
