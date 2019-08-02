package mesosphere.marathon
package api.v2

import mesosphere.marathon.state.{AbsolutePathId, PathId, RootGroup}
import mesosphere.mesos.ResourceMatcher.Role

object GroupNormalization {

  def visitRootGroup(conf: MarathonConf, groupUpdate: raml.GroupUpdate): raml.GroupUpdate = {

    // Visit children.
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(PathId.root)
      visitTopLevelGroup(conf, childGroup, absoluteChildGroupPath, conf.groupRoleBehavior(), conf.mesosRole())
    })

    // Visit apps.
    val normalizationConfig = AppNormalization.Configuration(conf, conf.mesosRole())
    val apps = groupUpdate.apps.map(_.map { app => visitApp(app, PathId.root, normalizationConfig) })

    // Set enforce role field and aggregate.
    groupUpdate.copy(enforceRole = Some(false), groups = children, apps = apps)
  }

  def visitTopLevelGroup(conf: MarathonConf, groupUpdate: raml.GroupUpdate, base: AbsolutePathId, groupRoleBehavior: GroupRoleBehavior, mesosRole: Role): raml.GroupUpdate = {
    // Infer enforce role field and default role for all apps.
    val enforceRole = effectiveEnforceRole(groupRoleBehavior, groupUpdate.enforceRole)
    val defaultRole = if (enforceRole) PathId(groupUpdate.id.get).root else mesosRole

    // Visit children.
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      visitChildGroup(conf, childGroup, absoluteChildGroupPath, defaultRole)
    })

    // Visit apps.
    val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)
    val apps = groupUpdate.apps.map(_.map { app => visitApp(app, base, normalizationConfig) })

    // Aggregate results.
    groupUpdate.copy(enforceRole = Some(enforceRole), groups = children, apps = apps)
  }

  def visitChildGroup(conf: MarathonConf, groupUpdate: raml.GroupUpdate, base: AbsolutePathId, defaultRole: Role): raml.GroupUpdate = {
    val enforceRole = groupUpdate.enforceRole.getOrElse(false)

    // Visit children.
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      visitChildGroup(conf, childGroup, absoluteChildGroupPath, defaultRole)
    })

    // Visit apps.
    val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)
    val apps = groupUpdate.apps.map(_.map { app => visitApp(app, base, normalizationConfig) })

    // Set enforce role and aggregate.
    groupUpdate.copy(enforceRole = Some(enforceRole), groups = children, apps = apps)
  }

  def visitApp(app: raml.App, absoluteGroupPath: AbsolutePathId, normalizationConfig: AppNormalization.Config): raml.App = {
    val validateAndNormalizeApp: Normalization[raml.App] = AppHelpers.appNormalization(normalizationConfig)(AppNormalization.withCanonizedIds(absoluteGroupPath))
    val normalizedAbsoluteId = PathId(app.id).canonicalPath(absoluteGroupPath).toString

    validateAndNormalizeApp.normalized(app.copy(id = normalizedAbsoluteId))
  }

  /**
    * Normalize the group update of an API call.
    *
    * @param conf The [[MarathonConf]] holding the default Mesos role and the default enforce group
    *             role behavior.
    * @param base The absolute path of the group being updated.
    * @param originalRootGroup The [[RootGroup]] before the update was applied.
    * @return The normalized group update.
    */
  def updateNormalization(conf: MarathonConf, base: AbsolutePathId, originalRootGroup: RootGroup): Normalization[raml.GroupUpdate] = Normalization { update =>
    // Only update if this is not a scale or rollback
    if (update.version.isEmpty && update.scaleBy.isEmpty) {
      if (base.isRoot) visitRootGroup(conf, update)
      else if (base.isTopLevel) visitTopLevelGroup(conf, update, base, conf.groupRoleBehavior(), conf.mesosRole())
      else {
        val defaultRole = inferDefaultRole(conf, base, originalRootGroup)
        visitChildGroup(conf, update, base, defaultRole)
      }
    } else update
  }

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    update.copy(enforceRole = Some(effectiveEnforceRole(conf.groupRoleBehavior(), update.enforceRole)))
  }

  /**
    * Infers the enforce role field for a top-level group based on the update value and the default behavior.
    *
    * @param conf The Marathon conf defining the default behavior.
    * @param maybeEnforceRole The role defined by the updated.
    * @return Whether or not to enforce the role.
    */
  private def effectiveEnforceRole(groupRoleBehavior: GroupRoleBehavior, maybeEnforceRole: Option[Boolean]): Boolean = {
    maybeEnforceRole.getOrElse {
      groupRoleBehavior match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
    }
  }

  /**
    * Determine the default role for a lower level group.
    *
    * @param conf The [[MarathonConf]] used to check the default Mesos role.
    * @param groupId The group id of the lower level group. Must not be root or top-level.
    * @param rootGroup The root group used to look up the default role.
    * @return The default role for all apps and pods.
    */
  private def inferDefaultRole(conf: MarathonConf, groupId: AbsolutePathId, rootGroup: RootGroup): Role = {
    require(!groupId.isTopLevel && !groupId.isRoot)
    rootGroup.group(groupId.rootPath).fold(conf.mesosRole()) { parentGroup =>
      if (parentGroup.enforceRole) groupId.root else conf.mesosRole()
    }
  }
}
