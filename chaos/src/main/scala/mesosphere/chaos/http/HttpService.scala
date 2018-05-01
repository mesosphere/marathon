package mesosphere.chaos.http

import com.google.common.util.concurrent.AbstractIdleService
import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * Wrapper for starting and stopping the HttpServer.
  */
class HttpService(val server: Server) extends AbstractIdleService {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  override def startUp(): Unit = {
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

  override def shutDown(): Unit = {
    log.debug("Shutting down HttpServer.")
    server.stop()
  }
}
