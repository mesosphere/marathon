package mesosphere.marathon.api

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import javax.ws.rs.core.Response

import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.HttpResponse
import mesosphere.marathon.state.PathId

/**
  * Base trait for authentication and authorization in http resource endpoints.
  */
trait AuthResource extends RestResource {

  def authenticator: Authenticator
  def authorizer: Authorizer

  def doIfAuthenticated(request: HttpServletRequest,
                        response: HttpServletResponse)(fn: Identity => Response): Response = {
    val requestWrapper = new RequestFacade(request)
    val identity = result(authenticator.authenticate(requestWrapper))
    identity.map(fn).getOrElse {
      val responseWrapper = new ResponseFacade
      authenticator.handleNotAuthenticated(requestWrapper, responseWrapper)
      responseWrapper.response
    }
  }

  def doIfAuthorized[Resource](request: HttpServletRequest,
                               response: HttpServletResponse,
                               action: AuthorizedAction[Resource],
                               resources: Resource*)(fn: Identity => Response): Response = {
    def isAllowed(id: Identity) = resources.forall(authorizer.isAuthorized(id, action, _))
    def response(fn: HttpResponse => Unit): Response = {
      val responseFacade = new ResponseFacade
      fn(responseFacade)
      responseFacade.response
    }

    val requestFacade = new RequestFacade(request)
    result(authenticator.authenticate(requestFacade)) match {
      case Some(identity) if isAllowed(identity) => fn(identity)
      case Some(identity)                        => response(authorizer.handleNotAuthorized(identity, requestFacade, _))
      case None                                  => response(authenticator.handleNotAuthenticated(requestFacade, _))
    }
  }

  def isAllowedToView(pathId: PathId)(implicit identity: Identity): Boolean = {
    authorizer.isAuthorized(identity, ViewAppOrGroup, pathId)
  }
}

