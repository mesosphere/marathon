package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.{ Directives => AkkaDirectives, _ }
import mesosphere.marathon.plugin.auth.{ AuthorizedAction, Authorizer, Identity, Authenticator => MarathonAuthenticator }
import mesosphere.marathon.plugin.http.{ HttpRequest => PluginRequest }

import scala.util.{ Failure, Success }

trait AuthDirectives extends AkkaDirectives {
  import AuthDirectives._

  /**
    * Using the active authentication plugin, authenticates the current user, yielding the identity
    *
    * If unsuccessful, rejects with NotAuthenticated
    *
    * Because this method takes an implicit, you explicitly call `.apply` when invoking it in a route; IE:
    *
    *     authenticated.apply { implicit identity =>
    *       ...
    *     }
    *
    * Note that an Authenticator is expected to be in scope
    */
  def authenticated(implicit authenticator: MarathonAuthenticator): Directive1[Identity] = extractRequest.flatMap { request =>
    extractClientIP.flatMap { clientIP =>
      val pluginRequest: PluginRequest = HttpPluginFacade.request(request, clientIP)
      onComplete(authenticator.authenticate(pluginRequest)).flatMap {
        case Success(Some(identity)) => provide(identity)
        case Success(None) => reject(NotAuthenticated(HttpPluginFacade.response(authenticator.handleNotAuthenticated(pluginRequest, _))))
        case Failure(_) => reject(AuthServiceUnavailable)
      }
    }
  }

  /**
    * Using the active Authorizer, check for authorization for the specified request
    *
    * Because this method takes implicits, you must explicitly call apply when applying the directive. IE:
    *
    *
    *     authenticated.apply { implicit identity =>
    *       ...
    *       // both an Identity and Authorizer are implicitly provided
    *       authorized(ViewResource, info.app).apply {
    *         ...
    *       }
    *       ...
    *     }
    *
    * @param action The action for which to check authorization for the given identity
    * @param resource The entity for which authorization should be checked
    */
  def authorized[Resource](action: AuthorizedAction[Resource], resource: Resource)(implicit authorizer: Authorizer, identity: Identity): Directive0 =
    if (authorizer.isAuthorized(identity, action, resource))
      pass
    else
      reject(NotAuthorized(HttpPluginFacade.response(authorizer.handleNotAuthorized(identity, _))))
}

object AuthDirectives {

  private[AuthDirectives] case object AuthServiceUnavailable extends Rejection
  private[AuthDirectives] case class NotAuthorized(toResponse: HttpResponse) extends Rejection
  private[AuthDirectives] case class NotAuthenticated(toResponse: HttpResponse) extends Rejection

  def handleAuthRejections: PartialFunction[Rejection, Route] = {
    case AuthServiceUnavailable => Directives.complete(StatusCodes.ServiceUnavailable -> "Auth Service currently not available.")
    case NotAuthorized(pluginResponse) => Directives.complete(pluginResponse)
    case NotAuthenticated(pluginResponse) => Directives.complete(pluginResponse)
  }
}
