package mesosphere.marathon
package util

import mesosphere.marathon.state.{PathId, RootGroup}

/**
  * Configures role enforcement on runSpecs, used for validation.
  *
  * If enforce role is false, the role must be set, but no further checking is done.
  *
  * @param enforceRole If true, the role on the runSpec must match the specified role, additionally the
  *                    acceptedResourceRole field can only contain one of ['*', &lt;role&gt;]
  * @param validRoles List of valid roles
  * @param defaultRole The default role to use if no role is specified on the service. Defaults to '*'
  */
case class RoleSettings(enforceRole: Boolean, validRoles: Set[String], defaultRole: String) {
}

object RoleSettings {
  /**
    * Returns the role settings for the service with the specified ID, based on the top-level group and the global config
    *
    * @param config Global config to provide defaultMesos role
    * @param servicePathId The pathId of the affected runSpec, used to determine a possible top-level group role
    * @param rootGroup The root group, used to access possible top-level groups and their settings
    *
    * @return A data set with valid roles, default role and a flag if the role should be enforced
    */
  def forService(config: MarathonConf, servicePathId: PathId, rootGroup: RootGroup): RoleSettings = {
    val defaultRole = config.mesosRole()

    if (servicePathId.parent.isRoot) {
      // We have a service in the root group, no enforced role here
      RoleSettings(enforceRole = false, validRoles = Set(defaultRole), defaultRole = defaultRole)
    } else {
      val topLevelGroupPath = servicePathId.rootPath
      val topLevelGroup = rootGroup.group(topLevelGroupPath)

      if (topLevelGroup.isEmpty) {
        // We don't have a top-level group, so we use just the default Role
        RoleSettings(enforceRole = false, validRoles = Set(defaultRole), defaultRole = defaultRole)
      } else {
        val group = topLevelGroup.get
        val defaultForEnforceFromConfig = false // TODO: Use value from config instead
        val enforceRole = group.enforceRole.getOrElse(defaultForEnforceFromConfig)

        if (enforceRole) {
          // With enforceRole, we only allow the top-level group role
          RoleSettings(enforceRole = true, validRoles = Set(group.id.root), defaultRole = group.id.root)
        } else {
          // Without enforce role, we allow both default and top-level group role
          // The default role depends on the config parameter
          val defaultRoleToUse = if (defaultForEnforceFromConfig) group.id.root else defaultRole
          RoleSettings(enforceRole = false, validRoles = Set(defaultRole, group.id.root), defaultRole = defaultRoleToUse)
        }
      }
    }
  }

}
