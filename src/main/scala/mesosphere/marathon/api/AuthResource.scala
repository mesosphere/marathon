package mesosphere.marathon.api

import java.net.URI
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.{ NewCookie, Response }

import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.{ HttpRequest, HttpResponse }
import mesosphere.marathon.state.PathId

import scala.collection.JavaConverters._

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

  private class RequestFacade(request: HttpServletRequest) extends HttpRequest {
    // Jersey will not allow calls to the request object from another thread
    // To circumvent that, we have to copy all data during creation
    val headers = request.getHeaderNames.asScala.map(header => header -> request.getHeaders(header).asScala.toSeq).toMap
    val path = request.getRequestURI
    val cookies = request.getCookies
    val params = request.getParameterMap
    override def header(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
    override def requestPath: String = path
    override def cookie(name: String): Option[String] = cookies.find(_.getName == name).map(_.getValue)
    override def queryParam(name: String): Seq[String] = params.asScala.get(name).map(_.toSeq).getOrElse(Seq.empty)
  }

  private class ResponseFacade extends HttpResponse {
    private[this] var builder = Response.status(Status.UNAUTHORIZED)
    override def header(name: String, value: String): Unit = builder.header(name, value)
    override def status(code: Int): Unit = builder = builder.status(code)
    override def sendRedirect(location: String): Unit = {
      builder.status(Status.TEMPORARY_REDIRECT).location(new URI(location))
    }
    override def cookie(name: String, value: String, maxAge: Int, secure: Boolean): Unit = {
      //scalastyle:off null
      builder.cookie(new NewCookie(name, value, null, null, null, maxAge.toInt, secure))
    }
    override def body(mediaType: String, bytes: Array[Byte]): Unit = {
      builder.`type`(mediaType)
      builder.entity(bytes)
    }
    def response: Response = builder.build()
  }
}

