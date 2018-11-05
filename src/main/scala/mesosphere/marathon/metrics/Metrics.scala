package mesosphere.marathon
package metrics

import akka.stream.scaladsl.Source
import mesosphere.marathon.metrics.current.{UnitOfMeasurement => DropwizardUnitOfMeasurement}
import java.time.Clock

import scala.concurrent.Future

trait Metric

trait Counter extends Metric {
  def increment(): Unit
  def increment(times: Long): Unit
}

trait Gauge extends Metric {
  def value(): Long
  def increment(by: Long = 1): Unit
  def decrement(by: Long = 1): Unit
}

trait SettableGauge extends Gauge {
  def setValue(value: Long): Unit
}

trait ClosureGauge extends Metric

trait MinMaxCounter extends Metric {
  def increment(): Unit
  def increment(times: Long): Unit
  def decrement(): Unit
  def decrement(times: Long): Unit
}

trait Meter extends Metric {
  def mark(): Unit
}

trait TimerAdapter extends Metric {
  def update(value: Long): Unit
}

trait Timer extends TimerAdapter {
  def apply[T](f: => Future[T]): Future[T]
  def forSource[T, M](f: => Source[T, M])(implicit clock: Clock = Clock.systemUTC): Source[T, M]
  def blocking[T](f: => T): T

  // value is in nanoseconds
  def update(value: Long): Unit
}

trait Metrics {
  def counter(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Counter
  def gauge(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): Gauge
  def closureGauge[N](name: String, currentValue: () => N,
    unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): ClosureGauge
  def settableGauge(name: String, unit: DropwizardUnitOfMeasurement = DropwizardUnitOfMeasurement.None): SettableGauge
  def meter(name: String): Meter
  def timer(name: String): Timer
}
