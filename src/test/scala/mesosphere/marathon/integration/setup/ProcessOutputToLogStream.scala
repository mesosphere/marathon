package mesosphere.marathon
package integration.setup

import java.time.Clock
import org.slf4j.{ LoggerFactory, Logger }
import ProcessOutputToLogStream._

import scala.sys.process.ProcessLogger
import scala.concurrent.duration._

object ProcessOutputToLogStream {
  /**
    * Handles rate limiting logic; calling the method `incrementAndCheck` both registers a log line and returns whether
    * or not the quota has been exceeded.
    *
    * Simple periodic quota mechanism. We keep a tally of the number of log lines for each period. When we enter the
    * next period, we reset. If we exceed the quota for the period, we start returning false.
    *
    * @return Whether or not the quota for this period has been exceeded
    */
  final class RateLimiter(log: Logger, maxLinesPerPeriod: Int, period: FiniteDuration, clock: Clock) {
    private val periodMillis = period.toMillis
    private[this] var nextPeriodStart = System.currentTimeMillis() + periodMillis
    private[this] var linesThisPeriod = 0
    def incrementAndCheck(): Boolean = synchronized {
      val now = clock.millis()
      if (now >= nextPeriodStart) {
        val suppressedLines = linesThisPeriod - maxLinesPerPeriod
        if (suppressedLines > 0)
          log.error(s"Logger was rate-limited; ${suppressedLines} lines were supressed")

        linesThisPeriod = 1
        nextPeriodStart = now + periodMillis
        false
      } else {
        linesThisPeriod += 1
        linesThisPeriod > maxLinesPerPeriod
      }
    }
  }
}

final case class ProcessOutputToLogStream(process: String, maxLinesPerPeriod: Int = 100, period: FiniteDuration = 1.second) extends ProcessLogger {
  private val log: Logger = LoggerFactory.getLogger(s"mesosphere.marathon.integration.process.$process")
  private val rateLimiter = new RateLimiter(log, maxLinesPerPeriod, period, Clock.systemUTC())

  override def out(getMsg: => String): Unit = if (log.isDebugEnabled()) {
    val msg = getMsg
    if (!rateLimiter.incrementAndCheck())
      log.debug(msg)
  }
  override def err(getMsg: => String): Unit = if (log.isWarnEnabled()) {
    val msg = getMsg
    if (!rateLimiter.incrementAndCheck())
      log.warn(msg)
  }
  override def buffer[T](f: => T): T = f
}
