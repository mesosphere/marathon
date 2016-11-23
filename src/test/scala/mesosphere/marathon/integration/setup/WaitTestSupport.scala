package mesosphere.marathon.integration.setup

import akka.actor.Scheduler
import mesosphere.marathon.util.Retry

import scala.concurrent.{ Await, ExecutionContext }
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

  def waitUntil(description: String, maxWait: FiniteDuration)(fn: => Boolean)(implicit scheduler: Scheduler, ctx: ExecutionContext) = {
    waitFor(description, maxWait) {
      if (fn) Some(true) else None
    }
  }

  def waitFor[T](description: String, maxWait: FiniteDuration)(fn: => Option[T])(implicit scheduler: Scheduler, ctx: ExecutionContext): T = {
    val result = Retry.blocking(description, Int.MaxValue, maxDelay = maxWait) {
      fn.getOrElse(throw new AssertionError(s"Waiting for $description took longer than $maxWait. Give up."))
    }
    Await.result(result, maxWait)
  }
}
