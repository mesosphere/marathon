package mesosphere.marathon.api

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.Response

import mesosphere.marathon.{ UnknownGroupException, UnknownAppException, AccessDeniedException }
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.HttpResponse

/**
  * Base trait for authentication and authorization in http resource endpoints.
  */
trait AuthResource extends RestResource {
  def authenticator: Authenticator
  def authorizer: Authorizer

  def authenticated(request: HttpServletRequest)(fn: Identity => Response): Response = {
    val requestWrapper = new RequestFacade(request)
    val maybeIdentity = result(authenticator.authenticate(requestWrapper))
    maybeIdentity.map { identity =>
      try {
        fn(identity)
      }
      catch {
        case e: AccessDeniedException => withResponseFacade(authorizer.handleNotAuthorized(identity, _))
      }
    }.getOrElse {
      withResponseFacade(authenticator.handleNotAuthenticated(requestWrapper, _))
    }
  }

  def checkAuthorization[T](action: AuthorizedAction[T],
                            maybeResource: Option[T],
                            ifNotExists: Exception)(implicit identity: Identity): Unit = {
    maybeResource match {
      case Some(resource) => checkAuthorization(action, resource)
      case None           => throw ifNotExists
    }
  }

  def withAuthorization[A, B >: A](action: AuthorizedAction[B],
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

  def checkAuthorization[A, B >: A](action: AuthorizedAction[B], resource: A)(implicit identity: Identity): A = {
    if (authorizer.isAuthorized(identity, action, resource)) resource
    else throw AccessDeniedException()
  }

  def isAuthorized[T](action: AuthorizedAction[T], resource: T)(implicit identity: Identity): Boolean = {
    authorizer.isAuthorized(identity, action, resource)
  }

  private[this] def withResponseFacade(fn: HttpResponse => Unit): Response = {
    val responseFacade = new ResponseFacade
    fn(responseFacade)
    responseFacade.response
  }
}

