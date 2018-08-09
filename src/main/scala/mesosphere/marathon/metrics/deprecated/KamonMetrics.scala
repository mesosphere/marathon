package mesosphere.marathon
package metrics.deprecated

import kamon.Kamon
import kamon.metric.instrument
import kamon.metric.instrument.{Time, UnitOfMeasurement}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.metrics.{ClosureGauge, Counter, Gauge, HistogramTimer, Metrics, MinMaxCounter, SettableGauge, Timer, TimerAdapter}
import mesosphere.marathon.metrics.current.{UnitOfMeasurement => DropwizardUnitOfMeasurement}

object KamonMetrics extends Metrics {
  implicit class KamonCounter(val counter: instrument.Counter) extends Counter {
    override def increment(): Unit = counter.increment()
    override def increment(times: Long): Unit = counter.increment(times)
  }

  override def deprecatedCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): Counter = {
    Kamon.metrics.counter(name(prefix, `class`, metricName), tags, unit)
  }
  override def deprecatedCounter(metricName: String): Counter = {
    Kamon.metrics.counter(metricName)
  }

  private implicit class KamonClosureGauge(val gauge: instrument.Gauge) extends ClosureGauge
  override def deprecatedClosureGauge(metricName: String, currentValue: () => Long): ClosureGauge = {
    Kamon.metrics.gauge(metricName)(currentValue)
  }

  override def deprecatedSettableGauge(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): SettableGauge = {
    AtomicGauge(name(prefix, `class`, metricName), unit, tags)
  }

  override def deprecatedSettableGauge(metricName: String): SettableGauge = {
    AtomicGauge(metricName)
  }

  implicit class KamonMinMaxCounter(val counter: instrument.MinMaxCounter) extends MinMaxCounter {
    override def increment(): Unit = counter.increment()
    override def increment(times: Long): Unit = counter.increment(times)
    override def decrement(): Unit = counter.decrement()
    override def decrement(times: Long): Unit = counter.decrement(times)
  }

  override def deprecatedMinMaxCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: UnitOfMeasurement = UnitOfMeasurement.Unknown): MinMaxCounter = {
    Kamon.metrics.minMaxCounter(name(prefix, `class`, metricName), tags, unit)
  }

  class KamonTimerAdapter(timer: instrument.Histogram, unit: UnitOfMeasurement) extends TimerAdapter {
    override def update(value: Long): Unit = timer.record(value)
  }

  override def deprecatedTimer(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: Time = Time.Nanoseconds): Timer = {
    val metric = Kamon.metrics.histogram(name(prefix, `class`, metricName), unit)
    val adapter = new KamonTimerAdapter(metric, unit)
    HistogramTimer(adapter)
  }

  def className(klass: Class[_]): String = {
    if (klass.getName.contains("$EnhancerByGuice$")) klass.getSuperclass.getName else klass.getName
  }

  def name(prefix: MetricPrefix, klass: Class[_], name: String): String = {
    s"${prefix.name}.${className(klass)}.$name".replace('$', '.').replaceAll("""\.+""", ".")
  }

  override def counter(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Counter =
    DummyMetrics.counter(name, unit)
  override def gauge(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Gauge =
    DummyMetrics.gauge(name, unit)
  override def closureGauge(name: String, currentValue: () => Long,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): ClosureGauge =
    DummyMetrics.closureGauge(name, currentValue, unit)
  override def settableGauge(
    name: String,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): SettableGauge =
    DummyMetrics.settableGauge(name, unit)
  override def timer(name: String): Timer = DummyMetrics.timer(name)
}
