package mesosphere.marathon.api

import java.net.URI
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import javax.ws.rs.core.Response.Status

import mesosphere.marathon.core.appinfo.AppSelector
import mesosphere.marathon.interface.auth._
import mesosphere.marathon.interface.http.{ HttpRequest, HttpResponse }
import mesosphere.marathon.state.{ PathId, AppDefinition }
import scala.collection.JavaConverters._
import javax.ws.rs.core.{ NewCookie, Response }

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

  def isAllowed(pathId: PathId)(implicit identity: Identity): Boolean = {
    authorizer.isAuthorized(identity, ViewAppOrGroup, pathId)
  }

  def selectAuthorized(fn: => AppSelector)(implicit identity: Identity): AppSelector = {
    AppSelector.forall(Seq(new AuthorizeSelector(identity), fn))
  }

  private class AuthorizeSelector(identity: Identity) extends AppSelector {
    override def matches(app: AppDefinition): Boolean = authorizer.isAuthorized(identity, ViewAppOrGroup, app.id)
  }

  private class RequestFacade(request: HttpServletRequest) extends HttpRequest {
    override def header(name: String): Seq[String] = request.getHeaders(name).asScala.toSeq
    override def requestPath: String = request.getRequestURI
    override def cookie(name: String): Option[String] = request.getCookies.find(_.getName == name).map(_.getValue)
    override def queryParam(name: String): Seq[String] = Option(request.getParameterValues(name))
      .map(_.toSeq).getOrElse(Seq.empty)
  }

  private class ResponseFacade extends HttpResponse {
    private[this] var builder = Response.status(Status.UNAUTHORIZED)
    override def header(name: String, value: String): Unit = builder.header(name, value)
    override def status(code: Int): Unit = builder = builder.status(code)
    override def sendRedirect(location: String): Unit = {
      builder.status(Status.TEMPORARY_REDIRECT).location(new URI(location))
    }
    override def cookie(name: String, value: String): Unit = builder.cookie(new NewCookie(name, value))
    def response: Response = builder.build()
  }
}

