package mesosphere.marathon
package metrics

import java.util.concurrent.atomic.AtomicLong

import kamon.Kamon
import kamon.metric.instrument.UnitOfMeasurement

/**
  * Prefer [[Metrics.atomicGauge]] over usage of the constructor directly
  */
case class AtomicGauge(name: String, unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown,
    tags: Map[String, String] = Map.empty) extends SettableGauge {

  private[this] val counter = new AtomicLong()

  // The current value of the `counter` will be pulled by Kamon when needed
  // using the following gauge definition
  Kamon.metrics.gauge(name, unitOfMeasurement) { counter.get() }

  def value(): Long = counter.get()

  def setValue(value: Long): this.type = {
    counter.set(value)
    this
  }

  def increment(by: Long = 1): this.type = {
    counter.addAndGet(by)
    this
  }

  def decrement(by: Long = 1): this.type = {
    counter.addAndGet(-by)
    this
  }
}
