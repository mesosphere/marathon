package mesosphere.marathon
package integration.setup

import org.slf4j.{ LoggerFactory, Logger }

import scala.sys.process.ProcessLogger

final case class ProcessOutputToLogStream(process: String, maxLinesPerPeriod: Int = 100, periodMillis: Long = 1000L) extends ProcessLogger {
  private[this] var nextFlush = System.currentTimeMillis() + periodMillis
  private[this] var callsSinceLastFlush = 0
  val log: Logger = LoggerFactory.getLogger(s"mesosphere.marathon.integration.process.$process")

  private def rateLimited(msg: String): Boolean = synchronized {
    val now = System.currentTimeMillis
    if (now > nextFlush) {
      val suppressedLines = callsSinceLastFlush - maxLinesPerPeriod
      if (suppressedLines > 0)
        log.error(s"ProcessLogger ${process} was rate limited; ${suppressedLines} lines were supressed")

      callsSinceLastFlush = 1
      nextFlush = now + periodMillis
      false
    } else {
      callsSinceLastFlush += 1
      callsSinceLastFlush > maxLinesPerPeriod
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
