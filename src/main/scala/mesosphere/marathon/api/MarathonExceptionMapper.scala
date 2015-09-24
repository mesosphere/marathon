package mesosphere.marathon.api

import javax.validation.{ ConstraintViolation, ConstraintViolationException }
import javax.ws.rs.ext.{ Provider, ExceptionMapper }
import javax.ws.rs.core.{ MediaType, Response }
import com.fasterxml.jackson.databind.JsonMappingException
import com.google.inject.Singleton
import org.slf4j.LoggerFactory

import scala.concurrent.TimeoutException
import mesosphere.marathon.{
  AppLockedException,
  BadRequestException,
  ConflictingChangeException,
  UnknownAppException
}
import com.sun.jersey.api.NotFoundException
import com.fasterxml.jackson.core.JsonParseException
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response.Status
import play.api.libs.json.{ Json, JsObject, JsResultException }

@Provider
@Singleton
class MarathonExceptionMapper extends ExceptionMapper[Exception] {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  def toResponse(exception: Exception): Response = {
    // WebApplicationException are things like invalid requests etc, no need to log a stack trace
    if (!exception.isInstanceOf[WebApplicationException]) {
      log.warn("mapping exception to status code", exception)
    }
    else {
      log.info("mapping exception to status code", exception)
    }

    Response
      .status(statusCode(exception))
      .entity(Json.stringify(entity(exception)))
      .`type`(MediaType.APPLICATION_JSON)
      .build
  }

  //scalastyle:off magic.number cyclomatic.complexity
  private def statusCode(exception: Exception): Int = exception match {
    //scalastyle:off magic.number
    case e: IllegalArgumentException     => 422 // Unprocessable entity
    case e: TimeoutException             => 504 // Gateway timeout
    case e: UnknownAppException          => 404 // Not found
    case e: AppLockedException           => 409 // Conflict
    case e: ConflictingChangeException   => 409 // Conflict
    case e: BadRequestException          => 400 // Bad Request
    case e: JsonParseException           => 400 // Bad Request
    case e: JsResultException            => 400 // Bad Request
    case e: ConstraintViolationException => 422 // Unprocessable entity
    case e: JsonMappingException         => 400 // Bad Request
    case e: WebApplicationException      => e.getResponse.getStatus
    case _                               => 500 // Internal server error
    //scalastyle:on
  }

  private def entity(exception: Exception): JsObject = exception match {
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
      val errors = e.errors.map {
        case (path, errs) => Json.obj("path" -> path.toString(), "errors" -> errs.map(_.message))
      }
      Json.obj(
        "message" -> s"Invalid JSON",
        "details" -> errors
      )
    case e: ConstraintViolationException =>
      def violationToError(violation: ConstraintViolation[_]): JsObject = {
        Json.obj(
          "attribute" -> violation.getPropertyPath.toString,
          "error" -> violation.getMessage
        )
      }

      import scala.collection.JavaConverters._
      Json.obj(
        "message" -> e.getMessage,
        "errors" -> e.getConstraintViolations.asScala.map(violationToError)
      )
    case e: WebApplicationException =>
      //scalastyle:off null
      if (Status.fromStatusCode(e.getResponse.getStatus) != null) {
        Json.obj("message" -> Status.fromStatusCode(e.getResponse.getStatus).getReasonPhrase)
      }
      else {
        Json.obj("message" -> e.getMessage)
      }
    case _ =>
      Json.obj("message" -> exception.getMessage)
  }
}
