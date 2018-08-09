package mesosphere.marathon
package metrics.current

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.codahale
import com.codahale.metrics
import com.codahale.metrics.MetricRegistry
import com.github.rollingmetrics.histogram.{HdrBuilder, OverflowResolver}
import kamon.metric.instrument.{Time, UnitOfMeasurement => KamonUnitOfMeasurement}
import mesosphere.marathon.metrics.deprecated.MetricPrefix
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.metrics.{ClosureGauge, Counter, Gauge, HistogramTimer, Metrics, MetricsConf, MinMaxCounter, SettableGauge, Timer, TimerAdapter}
import mesosphere.marathon.metrics.current.{UnitOfMeasurement => DropwizardUnitOfMeasurement}

class DropwizardMetrics(metricsConf: MetricsConf, registry: MetricRegistry) extends Metrics {
  override def deprecatedCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): Counter =
    DummyMetrics.deprecatedCounter(prefix, `class`, metricName, tags, unit)
  override def deprecatedCounter(metricName: String): Counter = DummyMetrics.deprecatedCounter(metricName)

  override def deprecatedClosureGauge(metricName: String, currentValue: () => Long): ClosureGauge =
    DummyMetrics.deprecatedClosureGauge(metricName, currentValue)

  override def deprecatedSettableGauge(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): SettableGauge =
    DummyMetrics.deprecatedSettableGauge(prefix, `class`, metricName, tags, unit)
  override def deprecatedSettableGauge(metricName: String): SettableGauge =
    DummyMetrics.deprecatedSettableGauge(metricName)

  override def deprecatedMinMaxCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): MinMaxCounter =
    DummyMetrics.deprecatedMinMaxCounter(prefix, `class`, metricName, tags, unit)

  override def deprecatedTimer(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: Time = Time.Nanoseconds): Timer =
    DummyMetrics.deprecatedTimer(prefix, `class`, metricName, tags, unit)

  private val namePrefix = metricsConf.metricsNamePrefix()
  private val histogramReservoirSignificantDigits = metricsConf.metricsHistogramReservoirSignificantDigits()
  private val histogramReservoirResetPeriodically = metricsConf.metricsHistogramReservoirResetPeriodically()
  private val histogramReservoirResettingIntervalMs = metricsConf.metricsHistogramReservoirResettingIntervalMs()
  private val histogramReservoirResettingChunks = metricsConf.metricsHistogramReservoirResettingChunks()

  implicit class DropwizardCounter(val counter: codahale.metrics.Counter) extends Counter {
    override def increment(): Unit = increment(1L)
    override def increment(times: Long): Unit = counter.inc(times)
  }
  def counter(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Counter = {
    registry.counter(constructName(name, "counter", unit))
  }

  class DropwizardClosureGauge(val name: String, fn: () => Long) extends ClosureGauge {
    registry.gauge(name, () => () => fn())
  }
  def closureGauge(name: String, fn: () => Long,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): ClosureGauge = {
    new DropwizardClosureGauge(constructName(name, "gauge", unit), fn)
  }

  class DropwizardSettableGauge(val name: String) extends SettableGauge {
    private[this] val register = new AtomicLong(0)
    registry.gauge(name, () => () => register.get())

    override def increment(by: Long): Unit = register.addAndGet(by)
    override def decrement(by: Long): Unit = increment(-by)
    override def value(): Long = register.get()
    override def setValue(value: Long): Unit = register.set(value)
  }
  def gauge(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Gauge = {
    new DropwizardSettableGauge(constructName(name, "gauge", unit))
  }
  def settableGauge(
    name: String,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): SettableGauge = {
    new DropwizardSettableGauge(constructName(name, "gauge", unit))
  }

  implicit class DropwizardTimerAdapter(val timer: metrics.Timer) extends TimerAdapter {
    override def update(value: Long): Unit = timer.update(value, TimeUnit.NANOSECONDS)
  }

  def timer(name: String): Timer = {
    val effectiveName = constructName(name, "timer", DropwizardUnitOfMeasurement.Time)

    def makeTimer(): metrics.Timer = {
      val reservoirBuilder = new HdrBuilder()
        .withSignificantDigits(histogramReservoirSignificantDigits)
        .withLowestDiscernibleValue(1)
        .withHighestTrackableValue(Long.MaxValue, OverflowResolver.REDUCE_TO_HIGHEST_TRACKABLE)
      if (histogramReservoirResetPeriodically) {
        if (histogramReservoirResettingChunks == 0)
          reservoirBuilder.resetReservoirPeriodically(Duration.ofMillis(histogramReservoirResettingIntervalMs))
        else
          reservoirBuilder.resetReservoirPeriodicallyByChunks(
            Duration.ofMillis(histogramReservoirResettingIntervalMs), histogramReservoirResettingChunks)
      }
      val reservoir = reservoirBuilder.buildReservoir()
      new metrics.Timer(reservoir)
    }

    HistogramTimer(registry.timer(effectiveName, () => makeTimer()))
  }

  private val validNameRegex = "^[a-zA-Z0-9\\-\\.]+$".r
  private def constructName(name: String, `type`: String, unit: DropwizardUnitOfMeasurement): String = {
    val unitSuffix = unit match {
      case DropwizardUnitOfMeasurement.None => ""
      case DropwizardUnitOfMeasurement.Time => ".seconds"
      case DropwizardUnitOfMeasurement.Memory => ".bytes"
    }
    val constructedName = s"$namePrefix.$name.${`type`}$unitSuffix"
    constructedName match {
      case validNameRegex() =>
      case _ => throw new IllegalArgumentException(s"$name is not a valid metric name")
    }
    constructedName
  }
}
