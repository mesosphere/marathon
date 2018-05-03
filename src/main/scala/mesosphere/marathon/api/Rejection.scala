package mesosphere.marathon
package api

import javax.servlet.http.HttpServletRequest
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, Identity}

/**
  * Exception which, when thrown, will cause the corresponding rejection type to be mapped to an API response.
  *
  * Mapping is done in [[MarathonExceptionMapper]]
  */
case class RejectionException(rejection: Rejection) extends Exception(s"Unhandled rejection: ${rejection}")

sealed trait Rejection {}
object Rejection {
  case class AccessDeniedRejection(authorizer: Authorizer, identity: Identity) extends Rejection
  case class NotAuthenticatedRejection(authenticator: Authenticator, request: HttpServletRequest) extends Rejection
  case object ServiceUnavailableRejection extends Rejection

}
