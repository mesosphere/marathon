package mesosphere.marathon.core.election.impl

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

  private val maxMillis = maximumValue toMillis
  private val initialMillis = initialValue toMillis

  private val log = LoggerFactory.getLogger(getClass.getName)
  private val v: AtomicLong = new AtomicLong(initialMillis)

  def value(): FiniteDuration = v.get() milliseconds

  def increase(): Unit = {
    def atomicConditionalIncrement(): Unit = {
      val current = v.get
      val next = current * 2
      if (current <= maxMillis && !v.compareAndSet(current, next))
        atomicConditionalIncrement
      else if (current <= maxMillis)
        log.info(s"Increasing $name backoff to $v")
    }
    atomicConditionalIncrement()
  }

  def reset(): Unit = synchronized {
    log.info(s"Reset $name backoff")
    v.set(initialMillis)
  }

}
