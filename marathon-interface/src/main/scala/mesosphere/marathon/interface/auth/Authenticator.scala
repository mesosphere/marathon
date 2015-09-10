package mesosphere.marathon.interface.auth

import mesosphere.marathon.interface.http.{ HttpResponse, HttpRequest }
import mesosphere.marathon.interface.plugin.Plugin

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
  * The authenticate method method is most probably one that will query external identity providers,
  * and uses a Future as result.
  */
trait Authenticator extends Plugin {

  /**
    * Read data from the http request and derive the calling identity.
    * @param request the request to read the identity information from.
    * @return Some(Identity) if the identity can be authenticated from the request or None if not.
    */
  def authenticate(request: HttpRequest): Future[Option[Identity]]

  /**
    * If the identity can not be derived from the request, this method is called.
    * This purpose of this method is to customize the response send to the user.
    * @param request the related request.
    * @param response the response to customize.
    */
  def handleNotAuthenticated(request: HttpRequest, response: HttpResponse)
}

