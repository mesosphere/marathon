package mesosphere.marathon
package integration.setup

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Milliseconds, Span }

import scala.concurrent.duration._

/**
  * Helpers which wait for conditions.
  */
object WaitTestSupport extends Eventually {
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

  def waitUntil(description: String, maxWait: FiniteDuration)(fn: => Boolean): Unit = {
    eventually(timeout(Span(maxWait.toMillis, Milliseconds))) {
      if (!fn) throw new RuntimeException(s"$description not satisfied")
    }
  }

  def waitUntil(description: String)(fn: => Boolean)(implicit patienceConfig: PatienceConfig): Unit = {
    eventually {
      if (!fn) throw new RuntimeException(s"$description not satisfied")
    }
  }
}
