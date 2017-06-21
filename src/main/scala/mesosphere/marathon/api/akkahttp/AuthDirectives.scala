package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.{ HttpMethods, HttpResponse, StatusCodes }
import akka.http.scaladsl.server.{ Directives => AkkaDirectives, _ }
import mesosphere.marathon.plugin.http.{ HttpRequest => PluginRequest }
import mesosphere.marathon.plugin.{ Group, RunSpec }
import mesosphere.marathon.plugin.auth.{ AuthorizedAction, AuthorizedResource, Authorizer, CreateGroup, CreateResource, CreateRunSpec, DeleteGroup, DeleteResource, DeleteRunSpec, Identity, UpdateGroup, UpdateResource, UpdateRunSpec, ViewGroup, ViewResource, ViewRunSpec, Authenticator => MarathonAuthenticator }

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

  /**
    * Using the active Authorizer, check for authorization for the specified request
    * The authorized action itself is derived from the http request method.
    *
    * Because this method takes implicits, you must explicitly call apply when applying the directive. IE:
    *
    *    authorized(info.app).apply {
    *      ...
    *    }
    *
    * @param resource The entity for which authorization should be checked
    */
  def authorized[Resource](resource: Resource)(implicit authorizer: Authorizer, identity: Identity, actionSet: AuthorizedActionSet[_ >: Resource]): Directive0 =
    extractAuthorizedAction(actionSet).flatMap(authorized(_, resource))

  /**
    * This will extract the authorized action for resource type R based on the HTTP method.
    * Use this extraction only, if the HTTP verb defines the underlying action.
    * @param actionSet the related action set for the given resource.
    * @tparam R the resource type
    * @return the authorized action, for the given http method. Otherwise reject.
    */
  def extractAuthorizedAction[R](implicit actionSet: AuthorizedActionSet[R]): Directive1[AuthorizedAction[R]] = extractRequest.flatMap { request =>
    import HttpMethods._
    request.method match {
      case GET | OPTIONS | HEAD => provide(actionSet.view)
      case POST => provide(actionSet.create)
      case PUT | PATCH => provide(actionSet.update)
      case DELETE => provide(actionSet.delete)
      case _ => reject
    }
  }

  // Bring action sets for all available resources into implicit scope
  implicit val groupAuthorizedActionSet: AuthorizedActionSet[Group] = AuthorizedActionSet(CreateGroup, UpdateGroup, DeleteGroup, ViewGroup)
  implicit val runSpecAuthorizedActionSet: AuthorizedActionSet[RunSpec] = AuthorizedActionSet(CreateRunSpec, UpdateRunSpec, DeleteRunSpec, ViewRunSpec)
  implicit val resourceAuthorizedActionSet: AuthorizedActionSet[AuthorizedResource] = AuthorizedActionSet(CreateResource, UpdateResource, DeleteResource, ViewResource)
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

  /**
    * This action set defines all actions for create, update, delete and view based on a given resource type.
    * @tparam Resource The resource type
    */
  case class AuthorizedActionSet[Resource](
    create: AuthorizedAction[Resource],
    update: AuthorizedAction[Resource],
    delete: AuthorizedAction[Resource],
    view: AuthorizedAction[Resource]
  )
}
