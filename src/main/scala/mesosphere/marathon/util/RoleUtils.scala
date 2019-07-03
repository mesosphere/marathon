package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.{AppDefinition, PathId, ResourceRole, RootGroup}

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
case class RoleEnforcement(
    enforceRole: Boolean = false,
    validRoles: Set[String] = Set.empty,
    defaultRole: String = ResourceRole.Unreserved) {
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
  def getRoleSettingsForService(config: MarathonConf, servicePathId: PathId, rootGroup: RootGroup): RoleEnforcement = {
    val defaultRole = config.mesosRole()

    // We have a service in the root group, no enforced role here
    if (servicePathId.parent.isRoot) return RoleEnforcement(validRoles = Set(defaultRole), defaultRole = defaultRole)
    val rootPath = servicePathId.rootPath

    // TODO: Add enforced role setting
    rootGroup.group(rootPath).map(group => {
      //      if (group.enforceRole) {
      //      RoleEnforcement(enforceRole = true, validRoles = Seq(group.id.root))
      //      } else {
      RoleEnforcement(validRoles = Set(defaultRole, group.id.root), defaultRole = defaultRole)
      //      }
    }).getOrElse(RoleEnforcement(validRoles = Set(defaultRole), defaultRole = defaultRole))
  }

  /**
    * Update roles on RunSpecs who don't have any roles defined.
    *
    * @param config Global config to provide defaultMesos role
    * @param originalRoot The root group, used to determine possible top-level group roles
    *
    * @return An updated root group in which all runSpecs have valid roles
    */
  def updateRoles(config: MarathonConf, originalRoot: RootGroup): RootGroup = {

    def updateApp(app: AppDefinition): Option[AppDefinition] = {
      if (app.role.isDefined) {
        None
      } else {
        val enforcedRole = getRoleSettingsForService(config, app.id, originalRoot)
        Some(app.copy(role = Some(enforcedRole.defaultRole)))
      }
    }

    def updatePod(pod: PodDefinition): Option[PodDefinition] = {
      if (pod.role.isDefined) {
        None
      } else {
        val enforcedRole = getRoleSettingsForService(config, pod.id, originalRoot)
        Some(pod.copy(role = Some(enforcedRole.defaultRole)))
      }
    }
    var root = originalRoot

    // Set default role on apps
    val updatedApps = root.transitiveApps.flatMap(updateApp)
    root = updatedApps.foldLeft(root) { (rootGroup, app) =>
      rootGroup.updateApp(app.id, _ => app, app.version)
    }

    // Set default role on pods
    val updatedPods = root.transitivePods.flatMap(updatePod)
    updatedPods.foldLeft(root) { (rootGroup, pod) =>
      rootGroup.updatePod(pod.id, _ => pod, pod.version)
    }

    root
  }
}
