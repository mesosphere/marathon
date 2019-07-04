package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state.{PathId, ResourceRole, RootGroup}

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
case class RoleSettings(
    enforceRole: Boolean = false,
    validRoles: Set[String] = Set.empty,
    defaultRole: String = ResourceRole.Unreserved) {
}

object RoleSettings {
  def forTest = RoleSettings(validRoles = Set(ResourceRole.Unreserved), defaultRole = ResourceRole.Unreserved)
}

object RoleUtils extends StrictLogging {

  /**
    * Returns the role settings for the service with the specified ID, based on the top-level group and the global config
    *
    * @param config Global config to provide defaultMesos role
    * @param servicePathId The pathId of the affected runSpec, used to determine a possible top-level group role
    * @param rootGroup The root group, used to access possible top-level groups and their settings
    *
    * @return A data set with valid roles, default role and a flag if the role should be enforced
    */
  def getRoleSettingsForService(config: MarathonConf, servicePathId: PathId, rootGroup: RootGroup): RoleSettings = {
    val defaultRole = config.mesosRole()

    // We have a service in the root group, no enforced role here
    if (servicePathId.parent.isRoot) return RoleSettings(validRoles = Set(defaultRole), defaultRole = defaultRole)

    val topLevelGroupPath = servicePathId.rootPath

    rootGroup.group(topLevelGroupPath).map(group => {
      if (group.enforceRole.getOrElse(false)) {
        RoleSettings(enforceRole = true, validRoles = Set(group.id.root), defaultRole = group.id.root)
      } else {
        RoleSettings(validRoles = Set(defaultRole, group.id.root), defaultRole = defaultRole)
      }
    }).getOrElse(RoleSettings(validRoles = Set(defaultRole), defaultRole = defaultRole))
  }

}
