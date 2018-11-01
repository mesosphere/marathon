package mesosphere.marathon
package metrics.current

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.codahale
import com.codahale.metrics
import com.codahale.metrics.MetricRegistry
import com.github.rollingmetrics.histogram.{HdrBuilder, OverflowResolver}
import mesosphere.marathon.metrics.{ClosureGauge, Counter, Gauge, HistogramTimer, Meter, Metrics, MetricsConf, SettableGauge, Timer, TimerAdapter}
import mesosphere.marathon.metrics.current.{UnitOfMeasurement => DropwizardUnitOfMeasurement}

import scala.util.matching.Regex

class DropwizardMetrics(metricsConf: MetricsConf, registry: MetricRegistry) extends Metrics {
  import DropwizardMetrics.constructName

  private val namePrefix = metricsConf.metricsNamePrefix()
  private val histogramReservoirHighestTrackableValue = metricsConf.metricsHistogramReservoirHighestTrackableValue()
  private val histogramReservoirSignificantDigits = metricsConf.metricsHistogramReservoirSignificantDigits()
  private val histogramReservoirResetPeriodically = metricsConf.metricsHistogramReservoirResetPeriodically()
  private val histogramReservoirResettingIntervalMs = metricsConf.metricsHistogramReservoirResettingIntervalMs()
  private val histogramReservoirResettingChunks = metricsConf.metricsHistogramReservoirResettingChunks()

  implicit class DropwizardCounter(val counter: codahale.metrics.Counter) extends Counter {
    override def increment(): Unit = increment(1L)
    override def increment(times: Long): Unit = counter.inc(times)
  }
  override def counter(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Counter = {
    registry.counter(constructName(namePrefix, name, "counter", unit))
  }

  override def closureGauge[N](name: String, fn: () => N,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): ClosureGauge = {
    class DropwizardClosureGauge(val name: String) extends ClosureGauge {
      registry.gauge(name, () => () => fn())
    }
    new DropwizardClosureGauge(constructName(namePrefix, name, "gauge", unit))
  }

  class DropwizardSettableGauge(val name: String) extends SettableGauge {
    private[this] val register = new AtomicLong(0)
    registry.gauge(name, () => () => register.get())

    override def increment(by: Long): Unit = register.addAndGet(by)
    override def decrement(by: Long): Unit = increment(-by)
    override def value(): Long = register.get()
    override def setValue(value: Long): Unit = register.set(value)
  }
  override def gauge(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Gauge = {
    new DropwizardSettableGauge(constructName(namePrefix, name, "gauge", unit))
  }
  override def settableGauge(
    name: String,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): SettableGauge = {
    new DropwizardSettableGauge(constructName(namePrefix, name, "gauge", unit))
  }

  implicit class DropwizardMeter(val meter: codahale.metrics.Meter) extends Meter {
    override def mark(): Unit = meter.mark()
  }
  override def meter(name: String): Meter = {
    registry.meter(constructName(namePrefix, name, "meter", DropwizardUnitOfMeasurement.None))
  }

  implicit class DropwizardTimerAdapter(val timer: metrics.Timer) extends TimerAdapter {
    override def update(value: Long): Unit = timer.update(value, TimeUnit.NANOSECONDS)
  }

  override def timer(name: String): Timer = {
    val effectiveName = constructName(namePrefix, name, "timer", DropwizardUnitOfMeasurement.Time)

    def makeTimer(): metrics.Timer = {
      val reservoirBuilder = new HdrBuilder()
        .withSignificantDigits(histogramReservoirSignificantDigits)
        .withLowestDiscernibleValue(1)
        .withHighestTrackableValue(histogramReservoirHighestTrackableValue, OverflowResolver.REDUCE_TO_HIGHEST_TRACKABLE)
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
}

object DropwizardMetrics {
  val validNameRegex: Regex = "^[a-zA-Z0-9\\-\\.]+$".r

  def constructName(prefix: String, name: String, `type`: String, unit: DropwizardUnitOfMeasurement): String = {
    val unitSuffix = unit match {
      case DropwizardUnitOfMeasurement.None => ""
      case DropwizardUnitOfMeasurement.Time => ".seconds"
      case DropwizardUnitOfMeasurement.Memory => ".bytes"
    }
    val constructedName = s"$prefix.$name.${`type`}$unitSuffix"
    constructedName match {
      case validNameRegex() =>
      case _ => throw new IllegalArgumentException(s"$name is not a valid metric name")
    }
    constructedName
  }
}
