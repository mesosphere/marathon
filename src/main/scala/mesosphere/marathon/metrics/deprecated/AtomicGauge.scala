package mesosphere.marathon
package metrics.deprecated

import java.util.concurrent.atomic.AtomicLong

import kamon.Kamon
import kamon.metric.instrument.UnitOfMeasurement
import mesosphere.marathon.metrics.SettableGauge

private[deprecated] case class AtomicGauge(name: String, unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown,
    tags: Map[String, String] = Map.empty) extends SettableGauge {

  private[this] val counter = new AtomicLong()

  // The current value of the `counter` will be pulled by Kamon when needed
  // using the following gauge definition
  Kamon.metrics.gauge(name, unitOfMeasurement) { counter.get() }

  def value(): Long = counter.get()

  def setValue(value: Long): Unit = counter.set(value)
  def increment(by: Long = 1): Unit = counter.addAndGet(by)
  def decrement(by: Long = 1): Unit = counter.addAndGet(-by)
}
