package mesosphere.marathon
package api

import java.lang.{Exception => JavaException}
import javax.ws.rs.{BadRequestException, WebApplicationException}
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{MediaType, Response}
import javax.ws.rs.ext.{ExceptionMapper, Provider}
import javax.ws.rs.NotFoundException

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.google.inject.Singleton
import mesosphere.marathon.api.v2.Validation._
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.StrictLogging
import play.api.libs.json.{JsResultException, JsValue, Json}

import scala.concurrent.TimeoutException

@Provider
@Singleton
class MarathonExceptionMapper extends ExceptionMapper[JavaException] with StrictLogging {

  private def exceptionToResponse(exception: JavaException): Response = {
    exception match {
      case e: NotFoundException =>
        // route is not found
        logger.debug("No Route Found", e)
      case e: WebApplicationException =>
        // things like invalid requests etc
        logger.warn("Invalid Request", e)
      case _ =>
        logger.error("Exception while processing request", exception)
    }
    val (statusCode, entity) = exceptionStatusEntity(exception)

    Response
      .status(statusCode)
      .entity(Json.stringify(entity))
      .`type`(MediaType.APPLICATION_JSON)
      .build
  }

  private def rejectionToResponse(rejection: Rejection): Response = rejection match {
    case Rejection.AccessDeniedRejection(authorizer, identity) =>
      ResponseFacade(authorizer.handleNotAuthorized(identity, _))
    case Rejection.NotAuthenticatedRejection(authenticator, request) =>
      val requestWrapper = new RequestFacade(request)
      ResponseFacade(authenticator.handleNotAuthenticated(requestWrapper, _))
    case Rejection.ServiceUnavailableRejection =>
      Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
  }

  def toResponse(exception: JavaException): Response = {
    exception match {
      case RejectionException(rejection) =>
        rejectionToResponse(rejection)
      case e =>
        exceptionToResponse(e)
    }
  }

  private def exceptionStatusEntity(exception: JavaException): (Int, JsValue) = {
    def defaultEntity = Json.obj("message" -> exception.getMessage)
    exception match {
      case _: BadRequestException => (BadRequest.intValue, defaultEntity)
      case e: NotFoundException =>
        (
          InternalServerError.intValue,
          Json.obj("message" -> "URI not found"))
      case e: AppLockedException =>
        (
          Conflict.intValue,
          Json.obj(
            "message" -> e.getMessage,
            "deployments" -> e.deploymentIds.map(id => Json.obj("id" -> id))))
      case e: JsonParseException =>
        (
          BadRequest.intValue,
          Json.obj(
            "message" -> "Invalid JSON",
            "details" -> e.getOriginalMessage))
      case e: JsonMappingException =>
        (
          BadRequest.intValue,
          Json.obj(
            "message" -> "Please specify data in JSON format",
            "details" -> e.getMessage))
      case e: JsResultException =>
        val status = if (e.errors.nonEmpty && e.errors.forall { case (_, validationErrors) => validationErrors.nonEmpty })
          // if all of the nested errors are validation-related then generate
          // an error code consistent with that generated for ValidationFailedException
          UnprocessableEntity.intValue
        else
          BadRequest.intValue

        (status, RestResource.entity(e.errors))
      case e: WebApplicationException =>
        val entity = Option(Status.fromStatusCode(e.getResponse.getStatus)) match {
          case None =>
            Json.obj("message" -> e.getMessage)
          case Some(status) =>
            Json.obj("message" -> status.getReasonPhrase)
        }
        (e.getResponse.getStatus(), entity)

      case ValidationFailedException(_, failure) =>
        (
          UnprocessableEntity.intValue,
          Json.toJson(failure))

      case _: TimeoutException => (ServiceUnavailable.intValue, defaultEntity)
      case _: PathNotFoundException => (NotFound.intValue, defaultEntity)
      case _: AppNotFoundException => (NotFound.intValue, defaultEntity)
      case _: PodNotFoundException => (NotFound.intValue, defaultEntity)
      case _: UnknownGroupException => (NotFound.intValue, defaultEntity)
      case _: ConflictingChangeException => (Conflict.intValue, defaultEntity)

      case _: IllegalArgumentException => (UnprocessableEntity.intValue, defaultEntity)
      case _: TooManyRunningDeploymentsException => (Forbidden.intValue, defaultEntity)

      case _ =>
        (InternalServerError.intValue, defaultEntity)
    }
  }
}
