package mesosphere.marathon
package api

import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{ MediaType, Response }
import javax.ws.rs.ext.{ ExceptionMapper, Provider }

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.google.inject.Singleton
import com.sun.jersey.api.NotFoundException
import mesosphere.marathon.api.v2.Validation._
import org.apache.http.HttpStatus._
import org.slf4j.LoggerFactory
import play.api.libs.json.{ JsResultException, JsValue, Json }

import scala.concurrent.TimeoutException

import java.lang.{ Exception => JavaException }

@Provider
@Singleton
class MarathonExceptionMapper extends ExceptionMapper[JavaException] {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  def toResponse(exception: JavaException): Response = {
    exception match {
      case e: NotFoundException =>
        // route is not found
        log.debug("No Route Found", e)
      case e: WebApplicationException =>
        // things like invalid requests etc
        log.warn("Invalid Request", e)
      case _ =>
        log.error("Exception while processing request", exception)
    }

    Response
      .status(statusCode(exception))
      .entity(Json.stringify(entity(exception)))
      .`type`(MediaType.APPLICATION_JSON)
      .build
  }

  private def statusCode(exception: JavaException): Int = exception match {
    case _: TimeoutException => SC_SERVICE_UNAVAILABLE
    case _: PathNotFoundException => SC_NOT_FOUND
    case _: AppNotFoundException => SC_NOT_FOUND
    case _: PodNotFoundException => SC_NOT_FOUND
    case _: UnknownGroupException => SC_NOT_FOUND
    case _: AppLockedException => SC_CONFLICT
    case _: ConflictingChangeException => SC_CONFLICT
    case _: BadRequestException => SC_BAD_REQUEST
    case _: JsonParseException => SC_BAD_REQUEST

    case JsResultException(errors) if errors.nonEmpty && errors.forall {
      case (_, validationErrors) => validationErrors.nonEmpty
    } =>
      // if all of the nested errors are validation-related then generate
      // an error code consistent with that generated for ValidationFailedException
      SC_UNPROCESSABLE_ENTITY

    case _: JsResultException => SC_BAD_REQUEST

    case _: JsonMappingException => SC_BAD_REQUEST
    case _: IllegalArgumentException => SC_UNPROCESSABLE_ENTITY
    case _: ValidationFailedException => SC_UNPROCESSABLE_ENTITY
    case e: WebApplicationException => e.getResponse.getStatus
    case _ => SC_INTERNAL_SERVER_ERROR
  }

  private def entity(exception: JavaException): JsValue = exception match {
    case e: NotFoundException =>
      Json.obj("message" -> s"URI not found: ${e.getNotFoundUri.getRawPath}")
    case e: AppLockedException =>
      Json.obj(
        "message" -> e.getMessage,
        "deployments" -> e.deploymentIds.map(id => Json.obj("id" -> id))
      )
    case e: JsonParseException =>
      Json.obj(
        "message" -> "Invalid JSON",
        "details" -> e.getOriginalMessage
      )
    case e: JsonMappingException =>
      Json.obj(
        "message" -> "Please specify data in JSON format",
        "details" -> e.getMessage
      )
    case e: JsResultException =>
      RestResource.entity(e.errors)
    case ValidationFailedException(obj, failure) => Json.toJson(failure)
    case e: WebApplicationException =>
      Option(Status.fromStatusCode(e.getResponse.getStatus)).fold {
        Json.obj("message" -> e.getMessage)
      } { status =>
        Json.obj("message" -> status.getReasonPhrase)
      }
    case _ =>
      Json.obj("message" -> exception.getMessage)
  }
}
