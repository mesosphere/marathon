package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.server.{ Directives => AkkaDirectives }
import akka.http.scaladsl.server._
import mesosphere.marathon.plugin.auth.{ Authenticator => MarathonAuthenticator, AuthorizedAction, Authorizer, Identity }
import mesosphere.marathon.plugin.http.{ HttpRequest => PluginRequest, HttpResponse => PluginResponse }

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
    val pluginRequest = toPluginRequest(request)
    onComplete(authenticator.authenticate(pluginRequest)).flatMap {
      case Success(Some(identity)) => provide(identity)
      case Success(None) => reject(NotAuthenticated(toResponse(authenticator.handleNotAuthenticated(pluginRequest, _))))
      case Failure(_) => reject(AuthServiceUnavailable)
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
    * @param action The entity for which authorization should be checked
    */
  def authorized[Resource](action: AuthorizedAction[Resource], resource: Resource)(implicit authorizer: Authorizer, identity: Identity): Directive0 =
    if (authorizer.isAuthorized(identity, action, resource))
      pass
    else
      reject(NotAuthorized(toResponse(authorizer.handleNotAuthorized(identity, _))))
}

object AuthDirectives {

  trait ToResponse {
    // TODO - MARATHON-7319 actually implement this!
  }
  private[AuthDirectives] case object AuthServiceUnavailable extends Rejection
  private[AuthDirectives] case class NotAuthorized(toResponse: ToResponse) extends Rejection
  private[AuthDirectives] case class NotAuthenticated(toResponse: ToResponse) extends Rejection

  def handleAuthRejections: PartialFunction[Rejection, Route] = {
    // TODO - MARATHON-7319 actually implement these!
    case AuthServiceUnavailable => Directives.complete(StatusCodes.EnhanceYourCalm -> "auth service unavailable (TODO)")
    case NotAuthorized(_) => Directives.complete(StatusCodes.EnhanceYourCalm -> "not authorized (TODO)")
    case NotAuthenticated(_) => Directives.complete(StatusCodes.EnhanceYourCalm -> "not authenticated (TODO)")
  }

  def toPluginRequest(request: HttpRequest): PluginRequest = new PluginRequest {
    override def method: String = request.method.value

    override def cookie(name: String): Option[String] = request.cookies.find(_.name == name).map(_.value)

    override def localPort: Int = 123

    override def remotePort: Int = 123

    override def header(name: String): Seq[String] = request.headers.filter(_.name == name).map(_.value())

    override def queryParam(name: String): Seq[String] = request.uri.query().filter(_._1 == name).map(_._2)

    override def localAddr: String = ""

    override def requestPath: String = request.uri.path.toString()

    override def remoteAddr: String = ""
  }

  def toResponse(action: (PluginResponse) => Unit): ToResponse = {
    val builder = new PluginResponse with ToResponse {

      override def cookie(name: String, value: String, maxAge: Int, secure: Boolean): Unit = ???

      override def body(mediaType: String, bytes: Array[Byte]): Unit = ???

      override def sendRedirect(url: String): Unit = ???

      override def header(header: String, value: String): Unit = ???

      override def status(code: Int): Unit = ???
    }
    action(builder)
    builder
  }
}
