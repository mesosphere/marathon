package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.{AppDefinition, PathId, RoleEnforcement, RootGroup}

object RoleUtils extends StrictLogging {

  def getEnforcedRoleForService(config: MarathonConf, servicePathId: PathId, rootGroup: RootGroup): RoleEnforcement = {
    val defaultRole = config.mesosRole()

    // We have a service in the root group, no enforced role here
    if (servicePathId.parent.isRoot) return RoleEnforcement(validRoles = Set(defaultRole))
    val rootPath = servicePathId.rootPath

    // TODO: Add enforced role setting
    rootGroup.group(rootPath).map(group => {
      //      if (group.enforceRole) {
      //      RoleEnforcement(enforceRole = true, validRoles = Seq(group.id.root))
      //      } else {
      RoleEnforcement(validRoles = Set(defaultRole, group.id.root))
      //      }
    }).getOrElse(RoleEnforcement(validRoles = Set(defaultRole)))
  }

  def updateRoles(config: MarathonConf, originalRoot: RootGroup): RootGroup = {

    def updateApp(app: AppDefinition): Option[AppDefinition] = {
      if (app.role.isDefined) {
        None
      } else {
        val enforcedRole = getEnforcedRoleForService(config, app.id, originalRoot)
        Some(app.copy(role = Some(enforcedRole.defaultRole)))
      }
    }

    def updatePod(pod: PodDefinition): Option[PodDefinition] = {
      if (pod.role.isDefined) {
        None
      } else {
        val enforcedRole = getEnforcedRoleForService(config, pod.id, originalRoot)
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
