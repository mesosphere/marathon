package mesosphere.marathon
package integration.setup

import org.slf4j.LoggerFactory

import scala.sys.process.ProcessLogger

case class ProcessOutputToLogStream(process: String) extends ProcessLogger {
  val log = LoggerFactory.getLogger(s"mesosphere.marathon.integration.process.$process")
  override def out(msg: => String): Unit = log.debug(msg)
  override def err(msg: => String): Unit = log.warn(msg)
  override def buffer[T](f: => T): T = f
}
