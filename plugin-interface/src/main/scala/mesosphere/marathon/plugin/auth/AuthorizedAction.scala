package mesosphere.marathon.plugin.auth

import mesosphere.marathon.plugin.{ Group, AppDefinition }

/**
  * Base trait for all actions in the system.
  * All actions are defined on a specific resource with a given Resource Type.
  *
  * @tparam Resource the type of the Resource.
  */
sealed trait AuthorizedAction[+Resource]

/**
  * The following objects will be passed to the Authorizer when an action affects an application, in order to identify
  * which CRUD action needs to be authorized.
  */
case object CreateApp extends AuthorizedAction[AppDefinition]
case object UpdateApp extends AuthorizedAction[AppDefinition]
case object DeleteApp extends AuthorizedAction[AppDefinition]
case object ViewApp extends AuthorizedAction[AppDefinition]

/**
  * The following objects will be passed to the Authorizer when an action affects group, in order to identify which CRUD
  * action needs to be authorized.
  */
case object CreateGroup extends AuthorizedAction[Group]
case object UpdateGroup extends AuthorizedAction[Group]
case object DeleteGroup extends AuthorizedAction[Group]
case object ViewGroup extends AuthorizedAction[Group]
