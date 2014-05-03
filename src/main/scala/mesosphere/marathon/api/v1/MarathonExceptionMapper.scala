package mesosphere.marathon.api.v1

import javax.ws.rs.ext.{Provider, ExceptionMapper}
import javax.ws.rs.core.{MediaType, Response}
import scala.concurrent.TimeoutException
import mesosphere.marathon.{BadRequestException, UnknownAppException}
import com.sun.jersey.api.NotFoundException
import com.fasterxml.jackson.core.JsonParseException
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response.Status
import org.apache.log4j.Logger

/**
 * @author Tobi Knaup
 */

@Provider
class MarathonExceptionMapper extends ExceptionMapper[Exception] {

  private[this] val log = Logger.getLogger(getClass.getName)

  def toResponse(exception: Exception): Response = {
    // WebApplicationException are things like invalid requests etc, no need to log a stack trace
    if (!exception.isInstanceOf[WebApplicationException]) {
      log.warn("", exception)
    }

    Response
      .status(statusCode(exception))
      .entity(entity(exception))
      .`type`(MediaType.APPLICATION_JSON)
      .build
  }

  private def statusCode(exception: Exception): Int = exception match {
    case e: IllegalArgumentException => 422 // Unprocessable entity
    case e: TimeoutException => 504 // Gateway timeout
    case e: UnknownAppException => 404 // Not found
    case e: BadRequestException => 400 // Bad Request
    case e: JsonParseException => 400 // Bad Request
    case e: WebApplicationException => e.getResponse.getStatus
    case _ => 500 // Internal server error
  }

  private def entity(exception: Exception): Any = exception match {
    case e: NotFoundException =>
      Map("message" -> s"URI not found: ${e.getNotFoundUri.getRawPath}")
    case e: JsonParseException =>
      Map("message" -> e.getOriginalMessage)
    case e: WebApplicationException =>
      if (e.getResponse.getEntity != null) {
        Map("message" -> e.getResponse.getEntity)
      } else {
        Map("message" -> Status.fromStatusCode(e.getResponse.getStatus).getReasonPhrase)
      }
    case _ =>
      Map("message" -> exception.getMessage)
  }
}
