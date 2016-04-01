package mesosphere.marathon.plugin.auth

import mesosphere.marathon.plugin.http.HttpResponse
import mesosphere.marathon.plugin.plugin.Plugin

/**
  * Base trait for all authorizer implementations.
  * An authorizer is able to authorize an action on a resource based on an identity:
  *
  * [[Identity]] is a custom implementation that represents a person or system that has access to Marathon.
  * [[AuthorizedAction]] is the action
  */
trait Authorizer extends Plugin {

  /**
    * Decide whether it is allowed for the given principal to perform the given action on the given resource.
    * @param principal the identity that tries to access the resource
    * @param action the action that the user tries to perform.
    * @param resource the resource the user wants to access.
    * @tparam Resource the type of the resource for action and resource.
    * @return true if the user is authorized to access the resource to do the defined action.
    */
  def isAuthorized[Resource](principal: Identity, action: AuthorizedAction[Resource], resource: Resource): Boolean

  /**
    * This method is called in the case that the identity is not authorized to access the resource.
    * The main purpose of this implementation is to customize the http response (e.g. response code, redirect etc.)
    * @param principal the identity that has tried to access a resource with a given action.
    * @param response the response to customize.
    */
  def handleNotAuthorized(principal: Identity, response: HttpResponse)
}

