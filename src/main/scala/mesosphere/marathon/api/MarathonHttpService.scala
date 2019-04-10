package mesosphere.marathon
package api

import com.google.common.util.concurrent.{AbstractIdleService, Service}
import com.typesafe.scalalogging.StrictLogging
import org.eclipse.jetty.server.Server

import scala.util.Try

/**
  * Wrapper for starting and stopping the HttpServer.
  */
class MarathonHttpService(val server: Server) extends AbstractIdleService with Service with StrictLogging {

  override def startUp(): Unit = {
    logger.info("Starting up HttpServer.")
    try {
      server.start()
    } catch {
      case e: Exception =>
        logger.error("Failed to start HTTP service", e)
        Try(server.stop())
        throw e
    }
  }

  override def shutDown(): Unit = {
    logger.info("Shutting down HttpServer.")
    server.stop()
  }
}
