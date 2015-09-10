package mesosphere.marathon.api

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import mesosphere.marathon.interface.auth.{ Authenticator, AuthorizedAction, Authorizer, Identity }
import mesosphere.marathon.interface.http.{ HttpRequest, HttpResponse }
import mesosphere.util.Mockito

import scala.concurrent.Future

class TestAuthFixture extends Mockito {

  type Auth = Authenticator with Authorizer

  var identity: Identity = new Identity { override def id: String = "marathon-auth-tester" }

  var authenticated: Boolean = true
  var authorized: Boolean = true

  val UnauthorizedStatus = 401
  val NotAuthenticatedStatus = 403

  def auth: Auth = new Authorizer with Authenticator {
    override def authenticate(request: HttpRequest): Future[Option[Identity]] = {
      Future.successful(if (authenticated) Some(identity) else None)
    }
    override def handleNotAuthenticated(request: HttpRequest, response: HttpResponse): Unit = {
      response.status(NotAuthenticatedStatus)
    }
    override def handleNotAuthorized(principal: Identity, request: HttpRequest, response: HttpResponse): Unit = {
      response.status(UnauthorizedStatus)
    }
    override def isAuthorized[Resource](principal: Identity, action: AuthorizedAction[Resource],
                                        resource: Resource): Boolean = authorized
  }

  var request: HttpServletRequest = mock[HttpServletRequest]
  var response: HttpServletResponse = mock[HttpServletResponse]
}
