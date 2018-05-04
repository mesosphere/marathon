package mesosphere.marathon
package api

import com.typesafe.scalalogging.StrictLogging
import org.eclipse.jetty.server.NCSARequestLog

class JettyRequestLog extends NCSARequestLog with StrictLogging {
  val lineSepLength = System.lineSeparator().length

  setLogLatency(true)

  override def write(requestEntry: String): Unit = {
    // Remove line separator because jul will add it
    logger.info(requestEntry.substring(0, requestEntry.length - lineSepLength))
  }
}
