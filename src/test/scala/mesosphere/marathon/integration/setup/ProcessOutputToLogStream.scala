package mesosphere.marathon
package integration.setup

import com.typesafe.scalalogging.StrictLogging

import scala.sys.process.ProcessLogger

case class ProcessOutputToLogStream(process: String) extends ProcessLogger with StrictLogging {
  override def out(msg: => String): Unit = logger.debug(msg)
  override def err(msg: => String): Unit = logger.warn(msg)
  override def buffer[T](f: => T): T = f
}
