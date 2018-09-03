package mesosphere.marathon
package metrics.dummy

import java.time.Clock

import akka.stream.scaladsl.Source
import kamon.metric.instrument.{Time, UnitOfMeasurement => KamonUnitOfMeasurement}
import mesosphere.marathon.metrics.{ClosureGauge, Counter, Gauge, Meter, Metrics, MinMaxCounter, SettableGauge, Timer}
import mesosphere.marathon.metrics.current.{UnitOfMeasurement => DropwizardUnitOfMeasurement}
import mesosphere.marathon.metrics.deprecated.MetricPrefix

import scala.concurrent.Future

object DummyMetrics extends Metrics {
  class DummyCounter extends Counter {
    override def increment(): Unit = ()
    override def increment(times: Long): Unit = ()
  }
  class DummyGauge extends SettableGauge {
    override def increment(by: Long): Unit = ()
    override def decrement(by: Long): Unit = ()
    override def value(): Long = 0L
    override def setValue(value: Long): Unit = ()
  }
  class DummyClosureGauge extends ClosureGauge
  class DummyMinMaxCounter extends MinMaxCounter {
    override def increment(): Unit = ()
    override def increment(times: Long): Unit = ()
    override def decrement(): Unit = ()
    override def decrement(times: Long): Unit = ()
  }
  class DummyMeter extends Meter {
    override def mark(): Unit = ()
  }
  class DummyTimer extends Timer {
    override def apply[T](f: => Future[T]): Future[T] = f
    override def blocking[T](f: => T): T = f
    override def forSource[T, M](f: => Source[T, M])(implicit clock: Clock): Source[T, M] = f
    override def update(value: Long): Unit = ()
  }

  override def deprecatedCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): Counter = new DummyCounter
  override def deprecatedCounter(metricName: String): Counter = new DummyCounter
  override def deprecatedClosureGauge(metricName: String, currentValue: () => Long,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): ClosureGauge = new DummyClosureGauge
  override def deprecatedSettableGauge(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): SettableGauge = new DummyGauge
  override def deprecatedSettableGauge(metricName: String): SettableGauge = new DummyGauge
  override def deprecatedMinMaxCounter(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty,
    unit: KamonUnitOfMeasurement = KamonUnitOfMeasurement.Unknown): MinMaxCounter = new DummyMinMaxCounter
  override def deprecatedTimer(prefix: MetricPrefix, `class`: Class[_], metricName: String,
    tags: Map[String, String] = Map.empty, unit: Time = Time.Nanoseconds): Timer = new DummyTimer
  override def deprecatedTimer(metricName: String): Timer = new DummyTimer

  override def counter(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Counter =
    new DummyCounter
  override def gauge(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Gauge =
    new DummyGauge
  override def closureGauge[N](name: String, currentValue: () => N,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): ClosureGauge = new DummyClosureGauge
  override def settableGauge(
    name: String,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): SettableGauge =
    new DummyGauge

  override def meter(name: String): Meter = new DummyMeter
  override def timer(name: String): Timer = new DummyTimer
}
