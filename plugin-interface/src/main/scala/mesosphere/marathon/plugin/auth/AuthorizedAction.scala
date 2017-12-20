package mesosphere.marathon
package plugin.auth

import mesosphere.marathon.plugin.{ Group, RunSpec }

/**
  * Base trait for all actions in the system.
  * All actions are defined on a specific resource with a given resource Type.
  *
  * @tparam R the type of the resource.
  */
sealed trait AuthorizedAction[-R]

/**
  * The following objects will be passed to the Authorizer when an action affects an application, in order to identify
  * which CRUD action needs to be authorized.
  */
case object CreateRunSpec extends AuthorizedAction[RunSpec]
case object UpdateRunSpec extends AuthorizedAction[RunSpec]
case object DeleteRunSpec extends AuthorizedAction[RunSpec]
case object ViewRunSpec extends AuthorizedAction[RunSpec]

/**
  * The following objects will be passed to the Authorizer when an action affects group, in order to identify which CRUD
  * action needs to be authorized.
  */
case object CreateGroup extends AuthorizedAction[Group]
case object UpdateGroup extends AuthorizedAction[Group]
case object DeleteGroup extends AuthorizedAction[Group]
case object ViewGroup extends AuthorizedAction[Group]

/**
  * The following object will be passed to the Authorizer when an action affects system resources
  */
case object CreateResource extends AuthorizedAction[AuthorizedResource]
case object UpdateResource extends AuthorizedAction[AuthorizedResource]
case object DeleteResource extends AuthorizedAction[AuthorizedResource]
case object ViewResource extends AuthorizedAction[AuthorizedResource]

