package mesosphere.marathon
package core.election.impl

import java.util.concurrent.atomic.AtomicLong
import scala.language.postfixOps

import org.slf4j.LoggerFactory

import scala.concurrent.duration._

trait Backoff {
  def value(): FiniteDuration
  def increase(): Unit
  def reset(): Unit
}

class ExponentialBackoff(
    initialValue: FiniteDuration = 0.5.seconds,
    maximumValue: FiniteDuration = 16.seconds,
    name: String = "unnamed") extends Backoff {

  private val maxNanos = maximumValue toNanos
  private val initialNanos = initialValue toNanos

  private val log = LoggerFactory.getLogger(getClass.getName)
  private val v: AtomicLong = new AtomicLong(initialNanos)

  def value(): FiniteDuration = v.get() nanoseconds

  def increase(): Unit = {
    def atomicConditionalIncrement(): Unit = {
      val current = v.get
      val next = current * 2
      if (next <= maxNanos && !v.compareAndSet(current, next))
        atomicConditionalIncrement
      else if (next <= maxNanos)
        log.info(s"Increasing $name backoff to ${(next nanoseconds).toCoarsest}")
    }
    atomicConditionalIncrement()
  }

  def reset(): Unit = synchronized {
    log.info(s"Reset $name backoff")
    v.set(initialNanos)
  }

}
