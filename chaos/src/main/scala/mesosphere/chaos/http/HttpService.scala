package mesosphere.chaos.http

import com.google.common.util.concurrent.AbstractIdleService
import com.google.inject.Inject
import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * Wrapper for starting and stopping the HttpServer.
  */
class HttpService @Inject() (val server: Server) extends AbstractIdleService {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  def startUp() {
    log.debug("Starting up HttpServer.")
    try {
      server.start()
    } catch {
      case e: Exception =>
        log.error("Failed to start HTTP service", e)
        Try(server.stop())
        throw e
    }
  }

  def shutDown() {
    log.debug("Shutting down HttpServer.")
    server.stop()
  }
}
