package mesosphere.chaos.validation

import javax.validation.ConstraintViolationException
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper
import javax.ws.rs.ext.Provider

@Provider
class ConstraintViolationExceptionMapper extends ExceptionMapper[ConstraintViolationException] {

  val UNPROCESSABLE_ENTITY = 422

  def toResponse(exception: ConstraintViolationException) = {
    val message = new ValidationErrorMessage(exception.getConstraintViolations)
    Response.status(UNPROCESSABLE_ENTITY)
      .entity(message)
      .build
  }
}
