package mesosphere.marathon.core.auth.impl

import mesosphere.marathon.plugin.auth.{ Authenticator, AuthorizedAction, Authorizer, Identity }
import mesosphere.marathon.plugin.http.{ HttpRequest, HttpResponse }

import scala.concurrent.Future

object AuthAllowEverything extends Authorizer with Authenticator {

  private[this] val defaultIdentity = Future.successful(Some(new Identity {}))

  override def authenticate(request: HttpRequest): Future[Option[Identity]] = defaultIdentity

  override def handleNotAuthenticated(request: HttpRequest, response: HttpResponse): Unit = {}

  override def handleNotAuthorized(principal: Identity, response: HttpResponse): Unit = {}

  override def isAuthorized[Resource](principal: Identity,
                                      action: AuthorizedAction[Resource],
                                      resource: Resource): Boolean = true
}

