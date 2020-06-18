package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state.Role
import mesosphere.marathon.state.{RootGroup, AbsolutePathId}

/**
  * Configures role enforcement on runSpecs, used for validation.
  *
  * If enforce role is false, the role must be set, but no further checking is done.
  *
  * @param validRoles List of valid roles
  * @param defaultRole The default role to use if no role is specified on the service. Defaults to '*'
  */
case class RoleSettings(validRoles: Set[Role], defaultRole: Role, previousRole: Option[String] = None, forceRoleUpdate: Boolean = false) {
  require(validRoles(defaultRole))
}

object RoleSettings extends StrictLogging {

  /**
    * Returns the role settings for the service with the specified ID, based on the top-level group and the global config
    *
    * @param config Global config to provide defaultMesos role
    * @param servicePathId The absolute pathId of the affected runSpec, used to determine a possible top-level group role
    * @param rootGroup The root group, used to access possible top-level groups and their settings
    * @param forceRoleUpdate For resident services, an update to the role may leave instances with their old roles and therefore requires an explicit force update
    *
    * @return A data set with valid roles, default role and a flag if the role should be enforced
    */
  def forService(config: MarathonConf, servicePathId: AbsolutePathId, rootGroup: RootGroup, forceRoleUpdate: Boolean): RoleSettings = {
    val defaultRole = config.mesosRole()

    if (servicePathId.parent.isRoot) {
      // We have a service in the root group, no enforced role here
      RoleSettings(validRoles = Set(defaultRole), defaultRole = defaultRole)
    } else {
      val topLevelGroupPath = servicePathId.rootPath
      val topLevelGroupRole = topLevelGroupPath.root
      val topLevelGroup = rootGroup.group(topLevelGroupPath)

      val oldServiceRole = rootGroup.runSpec(servicePathId).map(_.role)

      val defaultForEnforceFromConfig: Boolean = config.newGroupEnforceRole() match {
        case NewGroupEnforceRoleBehavior.Top => true
        case NewGroupEnforceRoleBehavior.Off => false
      }
      val enforceRole = topLevelGroup.fold(defaultForEnforceFromConfig)(_.enforceRole)

      if (topLevelGroup.isEmpty) {
        // TODO: Fetch top-level group even if it's in the process of creation
        // If the topLevelGroup is empty, we might have run into the case where a user creates
        // a group with runSpecs in a single action. The group isn't yet created, therefore we can't lookup
        // the enforceRole flag.
        logger.warn(
          s"Calculating role settings for $servicePathId, but unable to access top level group $topLevelGroupPath, using default for enforceRole flag: $enforceRole"
        )
      }

      // Look up any previously set group on the specified runSpec, and add that to the validRoles set if it exists
      val maybeExistingRole: Option[String] = rootGroup.runSpec(servicePathId).map(_.role)

      if (enforceRole) {
        // With enforceRole, we only allow the service to use the group-role or an existing role
        RoleSettings(
          validRoles = Set(topLevelGroupRole) ++ maybeExistingRole,
          defaultRole = topLevelGroupRole,
          previousRole = oldServiceRole,
          forceRoleUpdate = forceRoleUpdate
        )
      } else {
        // Without enforce role, we allow default role, group role, and the already existing role
        // We always default to the configured Mesos default role.
        val defaultRoleToUse = if (enforceRole) topLevelGroupRole else defaultRole
        RoleSettings(
          validRoles = Set(defaultRole, topLevelGroupRole) ++ maybeExistingRole,
          defaultRole = defaultRoleToUse,
          previousRole = oldServiceRole,
          forceRoleUpdate = forceRoleUpdate
        )
      }
    }
  }

  def residentRoleChangeWarningMessage(previousRole: String, newRole: String): String =
    "It is not possible to change the role for existing reservations. If you proceed with this change, all existing " +
      "instances will continue to run under the previous role, " + previousRole + ". Only new instances will be " +
      "allocated with the new role, " + newRole + ". In order to continue, retry your request with ?force=true"
}
