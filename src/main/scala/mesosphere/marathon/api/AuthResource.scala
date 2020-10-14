package mesosphere.marathon
package api

import javax.servlet.http.HttpServletRequest
import mesosphere.marathon.core.async.ExecutionContexts

import mesosphere.marathon.plugin.auth._
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Base trait for authentication and authorization in http resource endpoints.
  */
trait AuthResource extends RestResource {
  implicit val authenticator: Authenticator
  implicit val authorizer: Authorizer

  /**
    * Authenticate an HTTP request, asynchronously.
    *
    * @return If succeed, future with identity. If failed, returns failed future with a RejectionException.
    */
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
    }(ExecutionContexts.callerThread)
  }

  /**
    * Using the configured authenticator plugin, synchronously assert that the action is authorized for the provided
    * identity.
    *
    * @throw [[RejectionException]] on failure
    *
    * @param action The action to check
    * @param maybeResource Object associated with the action the user is attempting to perform. IE an app definition, pathId, or task.
    * @param ifNotExists Exception to throw if maybeResource is None
    *
    * @return Nothing on success
    */
  def checkAuthorization[T](action: AuthorizedAction[T], maybeResource: Option[T], ifNotExists: Exception)(implicit
      identity: Identity
  ): Unit = {
    maybeResource match {
      case Some(resource) => checkAuthorization(action, resource)
      case None => throw ifNotExists
    }
  }

  /**
    * Using the configured authenticator plugin, synchronously assert that the action is authorized for the provided
    * identity.
    *
    *
    */
  def withAuthorization[A, B >: A, R](action: AuthorizedAction[B], maybeResource: Option[A], ifNotExists: R)(
      fn: A => R
  )(implicit identity: Identity): R = {
    maybeResource match {
      case Some(resource) =>
        checkAuthorization(action, resource)
        fn(resource)
      case None => ifNotExists
    }
  }

  def withAuthorization[A, B >: A, R](action: AuthorizedAction[B], resource: A)(fn: => R)(implicit identity: Identity): R = {
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
