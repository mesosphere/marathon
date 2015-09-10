package mesosphere.marathon.core.auth

import mesosphere.marathon.interface.auth.{ Authenticator, AuthorizedAction, Identity, Authorizer }
import mesosphere.marathon.interface.http.{ HttpResponse, HttpRequest }

import scala.concurrent.Future

object AuthAllowEverything extends Authorizer with Authenticator {

  private[this] val defaultIdentity = Future.successful(Some(new Identity {
    override val id: String = "default-marathon-user"
  }))

  override def authenticate(request: HttpRequest): Future[Option[Identity]] = defaultIdentity

  override def handleNotAuthenticated(request: HttpRequest, response: HttpResponse): Unit = {
    //scalastyle:off magic.number
    response.status(401) //Unauthorized
  }

  override def handleNotAuthorized(principal: Identity, request: HttpRequest, response: HttpResponse): Unit = {
    //scalastyle:off magic.number
    response.status(403) //Forbidden
  }

  override def isAuthorized[Resource](principal: Identity,
                                      action: AuthorizedAction[Resource],
                                      resource: Resource): Boolean = {
    true
  }
}

