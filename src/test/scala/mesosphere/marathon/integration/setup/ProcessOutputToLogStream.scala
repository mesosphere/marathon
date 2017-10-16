package mesosphere.marathon
package integration.setup

import org.slf4j.{ LoggerFactory, Logger }

import scala.sys.process.ProcessLogger
import scala.concurrent.duration._

final case class ProcessOutputToLogStream(process: String, maxLinesPerPeriod: Int = 100, period: FiniteDuration = 1.second) extends ProcessLogger {
  private[this] var nextPeriodStart = System.currentTimeMillis() + periodMillis
  private[this] var callsThisPeriod = 0
  private val periodMillis = period.toMillis

  val log: Logger = LoggerFactory.getLogger(s"mesosphere.marathon.integration.process.$process")

  private def rateLimited(msg: String): Boolean = synchronized {
    val now = System.currentTimeMillis
    if (now > nextPeriodStart) {
      val suppressedLines = callsThisPeriod - maxLinesPerPeriod
      if (suppressedLines > 0)
        log.error(s"ProcessLogger ${process} was rate limited; ${suppressedLines} lines were supressed")

      callsThisPeriod = 1
      nextPeriodStart = now + periodMillis
      false
    } else {
      callsThisPeriod += 1
      callsThisPeriod > maxLinesPerPeriod
    }
  }

  override def out(getMsg: => String): Unit = if (log.isDebugEnabled()) {
    val msg = getMsg
    if (!rateLimited(msg))
      log.debug(msg)
  }
  override def err(getMsg: => String): Unit = if (log.isWarnEnabled()) {
    val msg = getMsg
    if (!rateLimited(msg))
      log.warn(msg)
  }
  override def buffer[T](f: => T): T = f
}
