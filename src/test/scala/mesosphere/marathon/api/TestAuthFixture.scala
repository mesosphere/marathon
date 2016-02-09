package mesosphere.marathon.api

import java.util.Collections
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedAction, Authorizer, Identity }
import mesosphere.marathon.plugin.http.{ HttpRequest, HttpResponse }
import mesosphere.marathon.test.Mockito

import scala.concurrent.Future

class TestAuthFixture extends Mockito {

  type Auth = Authenticator with Authorizer

  var identity: Identity = new Identity {}

  var authenticated: Boolean = true
  var authorized: Boolean = true
  var authFn: Any => Boolean = { _ => true }

  val UnauthorizedStatus = 401
  val NotAuthenticatedStatus = 403

  def auth: Auth = new Authorizer with Authenticator {
    override def authenticate(request: HttpRequest): Future[Option[Identity]] = {
      Future.successful(if (authenticated) Some(identity) else None)
    }
    override def handleNotAuthenticated(request: HttpRequest, response: HttpResponse): Unit = {
      response.status(NotAuthenticatedStatus)
    }
    override def handleNotAuthorized(principal: Identity, response: HttpResponse): Unit = {
      response.status(UnauthorizedStatus)
    }
    override def isAuthorized[Resource](principal: Identity,
                                        action: AuthorizedAction[Resource],
                                        resource: Resource): Boolean = {
      authorized && authFn(resource)
    }
  }

  var request: HttpServletRequest = {
    val req = mock[HttpServletRequest]
    req.getHeaderNames returns Collections.emptyEnumeration()
    req.getHeaders(any) returns Collections.emptyEnumeration()
    req
  }
  var response: HttpServletResponse = mock[HttpServletResponse]
}
