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

/**
  * Models known reasons why an API request could be confused.
  */
sealed trait Rejection {}
object Rejection {
  /**
    * The request was authenticated, but access was not allowed to the resource.
    */
  case class AccessDeniedRejection(authorizer: Authorizer, identity: Identity) extends Rejection

  /**
    * Authentication was required, but failed.
    */
  case class NotAuthenticatedRejection(authenticator: Authenticator, request: HttpServletRequest) extends Rejection

  /**
    * A dependent service (IE authentication) was not available, and therefore the request could not be processed.
    */
  case object ServiceUnavailableRejection extends Rejection
}
