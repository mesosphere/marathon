package mesosphere.marathon.api.v1

import javax.ws.rs.ext.{Provider, ExceptionMapper}
import javax.ws.rs.core.Response
import scala.concurrent.TimeoutException
import mesosphere.marathon.UnknownAppException
import java.util.logging.{Level, Logger}

/**
 * @author Tobi Knaup
 */

@Provider
class MarathonExceptionMapper extends ExceptionMapper[Exception] {

  private[this] val log = Logger.getLogger(getClass.getName)

  def toResponse(exception: Exception): Response = {
    log.log(Level.WARNING, "", exception)
    val statusCode = exception match {
      case e: IllegalArgumentException => 422  // Unprocessable entity
      case e: TimeoutException => 504 // Gateway timeout
      case e: UnknownAppException => 404
      case _ => 500 // Internal server error
    }
    val entity = Map("message" -> exception.getMessage)
    Response.status(statusCode).entity(entity).build
  }
}
