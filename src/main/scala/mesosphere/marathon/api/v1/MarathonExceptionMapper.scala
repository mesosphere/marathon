package mesosphere.marathon.api.v1

import javax.ws.rs.ext.{Provider, ExceptionMapper}
import javax.ws.rs.core.{MediaType, Response}
import scala.concurrent.TimeoutException
import mesosphere.marathon.UnknownAppException
import java.util.logging.{Level, Logger}
import com.sun.jersey.api.NotFoundException

/**
 * @author Tobi Knaup
 */

@Provider
class MarathonExceptionMapper extends ExceptionMapper[Exception] {

  private[this] val log = Logger.getLogger(getClass.getName)

  def toResponse(exception: Exception): Response = {
    log.log(Level.WARNING, "", exception)
    val statusCode = exception match {
      case e: IllegalArgumentException => 422 // Unprocessable entity
      case e: TimeoutException => 504 // Gateway timeout
      case e: UnknownAppException => 404 // Not found
      case e: NotFoundException => 404 // Not found
      case _ => 500 // Internal server error
    }
    val entity = exception match {
      case e: NotFoundException => Map("message" -> s"URI not found: ${e.getNotFoundUri.getRawPath}")
      case _ => Map("message" -> exception.getMessage)
    }
    Response.status(statusCode).`type`(MediaType.APPLICATION_JSON).entity(entity).build
  }
}
