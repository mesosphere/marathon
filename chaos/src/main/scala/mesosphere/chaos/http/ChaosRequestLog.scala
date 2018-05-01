package mesosphere.chaos.http

import org.eclipse.jetty.server.NCSARequestLog
import org.slf4j.LoggerFactory

class ChaosRequestLog extends NCSARequestLog {

  val lineSepLength = System.lineSeparator().length

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  setLogLatency(true)

  override def write(requestEntry: String) {
    // Remove line separator because jul will add it
    log.info(requestEntry.substring(0, requestEntry.length - lineSepLength))
  }
}
