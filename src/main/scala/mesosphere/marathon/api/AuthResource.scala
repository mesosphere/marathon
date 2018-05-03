package mesosphere.marathon
package api

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.Response

import mesosphere.marathon.plugin.auth._
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Base trait for authentication and authorization in http resource endpoints.
  */
trait AuthResource extends RestResource {
  implicit val authenticator: Authenticator
  implicit val authorizer: Authorizer

  def authenticatedAsync(request: HttpServletRequest): Future[Identity] = {
    val requestWrapper = new RequestFacade(request)
    val authenticationRequest = authenticator.authenticate(requestWrapper)

    authenticationRequest.transform {
      case Success(Some(identity)) =>
        Success(identity)
      case Success(None) =>
        Failure(RejectionException(Rejection.NotAuthenticatedRejection(authenticator, request)))
      case Failure(e) =>
        Failure(RejectionException(Rejection.ServiceUnavailableRejection))
    }
  }

  def authenticated(request: HttpServletRequest)(fn: Identity => Response): Response = result {
    authenticatedAsync(request).
      map(fn)
  }

  def checkAuthorization[T](
    action: AuthorizedAction[T],
    maybeResource: Option[T],
    ifNotExists: Exception)(implicit identity: Identity): Unit = {
    maybeResource match {
      case Some(resource) => checkAuthorization(action, resource)
      case None => throw ifNotExists
    }
  }

  def withAuthorization[A, B >: A](
    action: AuthorizedAction[B],
    maybeResource: Option[A],
    ifNotExists: Response)(fn: A => Response)(implicit identity: Identity): Response =
    {
      maybeResource match {
        case Some(resource) =>
          checkAuthorization(action, resource)
          fn(resource)
        case None => ifNotExists
      }
    }

  def withAuthorization[A, B >: A](
    action: AuthorizedAction[B],
    resource: A)(fn: => Response)(implicit identity: Identity): Response = {
    checkAuthorization(action, resource)
    fn
  }

  def checkAuthorization[A, B >: A](action: AuthorizedAction[B], resource: A)(implicit identity: Identity): A = {
    if (authorizer.isAuthorized(identity, action, resource)) resource
    else throw RejectionException(Rejection.AccessDeniedRejection(authorizer, identity))
  }

  def isAuthorized[T](action: AuthorizedAction[T], resource: T)(implicit identity: Identity): Boolean = {
    authorizer.isAuthorized(identity, action, resource)
  }
}

