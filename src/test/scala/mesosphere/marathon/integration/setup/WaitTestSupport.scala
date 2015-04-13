package mesosphere.marathon.integration.setup

import scala.concurrent.duration.FiniteDuration

/**
  * Helpers which wait for conditions.
  */
object WaitTestSupport {
  def validFor(description: String, until: FiniteDuration)(valid: => Boolean): Boolean = {
    val deadLine = until.fromNow
    def checkValid(): Boolean = {
      if (!valid) throw new IllegalStateException(s"$description not valid for $until. Give up.")
      if (deadLine.isOverdue()) true else {
        Thread.sleep(100)
        checkValid()
      }
    }
    checkValid()
  }

  def waitUntil(description: String, maxWait: FiniteDuration)(fn: => Boolean) = {
    waitFor(description, maxWait) {
      if (fn) Some(true) else None
    }
  }

  def waitFor[T](description: String, maxWait: FiniteDuration)(fn: => Option[T]): T = {
    val deadLine = maxWait.fromNow
    def next(): T = {
      if (deadLine.isOverdue()) throw new AssertionError(s"Waiting for $description took longer than $maxWait. Give up.")
      fn match {
        case Some(t) => t
        case None    => Thread.sleep(100); next()
      }
    }
    next()
  }
}
