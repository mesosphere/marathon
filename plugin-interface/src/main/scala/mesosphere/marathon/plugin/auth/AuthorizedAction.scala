package mesosphere.marathon.plugin.auth

import mesosphere.marathon.plugin.PathId

/**
  * Base trait for all actions in the system.
  * All actions are defined on a specific resource with a given Resource Type.
  *
  * @tparam Resource the type of the Resource.
  */
sealed trait AuthorizedAction[Resource]

/**
  * If an Application or Group is created with a given PathId.
  */
case object CreateAppOrGroup extends AuthorizedAction[PathId]

/**
  * If an Application or Group is updated with a given PathId.
  */
case object UpdateAppOrGroup extends AuthorizedAction[PathId]

/**
  * If an Application or Group is deleted with a given PathId.
  */
case object DeleteAppOrGroup extends AuthorizedAction[PathId]

/**
  * If an Application or Group is viewed with a given PathId.
  */
case object ViewAppOrGroup extends AuthorizedAction[PathId]

/**
  * If a Task is killed from an Application with a given PathId.
  */
case object KillTask extends AuthorizedAction[PathId]

