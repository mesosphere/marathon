package mesosphere.marathon.plugin.auth

import mesosphere.marathon.plugin.http.{ HttpRequest, HttpResponse }
import mesosphere.marathon.plugin.plugin.Plugin

import scala.concurrent.Future

/**
  * Base trait for all authenticator implementations.
  * Every authenticator can get data from an HTTP request and derive an Identity.
  * The Identity usually is maintained in a separate Identity and Access Management System (e.g. LDAP)
  *
  * The Identity is passed to the [[Authorizer]].
  * It should hold all information to make decisions possible for authorization.
  *
  * The Resources will make sure, that the [[Authenticator#authenticate]]
  * will call this method only once per request.
  *
  */
trait Authenticator extends Plugin {

  /**
    * Read data from the http request and derive the calling identity.
    *
    * If this request can be authenticated, than a Some(identity) is returned, otherwise None.
    *
    * The authenticate method method is most probably one that will query external identity providers,
    * and uses a Future as result, since asynchronous communication is involved.
    *
    * @param request the request to read the identity information from.
    * @return Some(Identity) if the identity can be authenticated from the request or None if not.
    */
  def authenticate(request: HttpRequest): Future[Option[Identity]]

  /**
    * This method will be called if the identity can not be derived from a request.
    * This purpose of this method is to customize the response sent to the user.
    * @param request the related request.
    * @param response the response to customize.
    */
  def handleNotAuthenticated(request: HttpRequest, response: HttpResponse)
}

