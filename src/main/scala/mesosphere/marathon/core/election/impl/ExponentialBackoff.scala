package mesosphere.marathon.core.election.impl

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
  private val log = LoggerFactory.getLogger(getClass.getName)
  private var v = initialValue

  def value(): FiniteDuration = synchronized { v }

  def increase(): Unit = synchronized {
    if (v <= maximumValue) {
      v *= 2
      log.info(s"Increasing $name backoff to $v")
    }
  }

  def reset(): Unit = synchronized {
    log.info(s"Reset $name backoff")
    v = initialValue
  }
}
