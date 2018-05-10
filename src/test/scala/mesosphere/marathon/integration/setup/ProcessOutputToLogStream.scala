package mesosphere.marathon
package integration.setup

import com.typesafe.scalalogging.{Logger, StrictLogging}

import scala.sys.process.ProcessLogger

case class ProcessOutputToLogStream(process: String) extends ProcessLogger with StrictLogging {
  override val logger = Logger(s"mesosphere.marathon.integration.process.$process")
  override def out(msg: => String): Unit = logger.debug(msg)
  override def err(msg: => String): Unit = logger.warn(msg)
  override def buffer[T](f: => T): T = f
}
