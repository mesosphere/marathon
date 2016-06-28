package mesosphere.marathon.util

import java.util.concurrent.{ TimeoutException => JavaTimeoutException }

/**
  * Extension of a TimeoutException that allows a cause
  */
case class TimeoutException(reason: String, cause: Throwable) extends JavaTimeoutException(reason) {
  def this(reason: String) = this(reason, null) // scalastyle:off null
  override def getCause: Throwable = cause
}

object TimeoutException {
  def apply(reason: String): TimeoutException = new TimeoutException(reason)
}
