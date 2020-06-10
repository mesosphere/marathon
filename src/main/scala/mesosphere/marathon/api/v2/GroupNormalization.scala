package mesosphere.marathon
package api.v2

import mesosphere.marathon.state.{AbsolutePathId, PathId, RootGroup}
import mesosphere.mesos.ResourceMatcher.Role

case class GroupNormalization(conf: MarathonConf, originalRootGroup: RootGroup) {

  /**
    * Normalize the root group update.
    *
    * @param conf The [[MarathonConf]]
    * @param groupUpdate The update for the root group.
    * @return a normalized [[raml.GroupUpdate]] if the update is for /.
    */
  def visitRootGroup(conf: MarathonConf, groupUpdate: raml.GroupUpdate): raml.GroupUpdate = {

    // Visit children.
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(PathId.root)
      visitTopLevelGroup(conf, childGroup, absoluteChildGroupPath, conf.newGroupEnforceRole(), conf.mesosRole())
    })

    // Visit apps.
    val normalizationConfig = AppNormalization.Configuration(conf, conf.mesosRole())
    val apps = groupUpdate.apps.map(_.map { app => visitApp(app, PathId.root, normalizationConfig, false) })

    // Set enforce role field and aggregate.
    groupUpdate.copy(enforceRole = Some(false), groups = children, apps = apps)
  }

  /**
    * Normalize and update for top-level groups.
    *
    * @param conf The [[MarathonConf]]
    * @param groupUpdate The actual update.
    * @param groupPath The absolute path of the group that is updated.
    * @param groupRoleBehavior Defines the default for the enforce role field of groups.
    * @param mesosRole The default Mesos role define via [[MarathonConf]].
    * @return a normalized group update.
    */
  def visitTopLevelGroup(
      conf: MarathonConf,
      groupUpdate: raml.GroupUpdate,
      groupPath: AbsolutePathId,
      groupRoleBehavior: NewGroupEnforceRoleBehavior,
      mesosRole: Role
  ): raml.GroupUpdate = {
    // Infer enforce role field and default role for all apps.
    val enforceRole = effectiveEnforceRole(groupRoleBehavior, groupUpdate.enforceRole)
    val defaultRole =
      if (enforceRole) groupUpdate.id.map(PathId(_)).getOrElse(PathId.relativeEmpty).canonicalPath(groupPath).root else mesosRole

    // Visit children.
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(groupPath)
      visitChildGroup(conf, childGroup, absoluteChildGroupPath, defaultRole, enforceRole)
    })

    // Visit apps.
    val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)
    val apps = groupUpdate.apps.map(_.map { app => visitApp(app, groupPath, normalizationConfig, enforceRole) })

    // Aggregate results.
    groupUpdate.copy(enforceRole = Some(enforceRole), groups = children, apps = apps)
  }

  /**
    * Normalize groups that are neither root nor top-level.
    *
    * @param conf The [[MarathonConf]].
    * @param groupUpdate The update for the group.
    * @param groupPath The path of the groups that is updated.
    * @param defaultRole The default Mesos role for all apps in this groups.
    * @param enforceRole Whether the top-level group enforces the app role.
    * @return a normalized update.
    */
  def visitChildGroup(
      conf: MarathonConf,
      groupUpdate: raml.GroupUpdate,
      groupPath: AbsolutePathId,
      defaultRole: Role,
      enforceRole: Boolean
  ): raml.GroupUpdate = {
    val enforceRole = groupUpdate.enforceRole.getOrElse(false)

    // Visit children.
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(groupPath)
      visitChildGroup(conf, childGroup, absoluteChildGroupPath, defaultRole, enforceRole)
    })

    // Visit apps.
    val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)
    val apps = groupUpdate.apps.map(_.map { app => visitApp(app, groupPath, normalizationConfig, enforceRole) })

    // Set enforce role and aggregate.
    groupUpdate.copy(enforceRole = Some(enforceRole), groups = children, apps = apps)
  }

  /**
    * Normalize and validate app.
    *
    * @param app The app of a group update.
    * @param absoluteGroupPath The group path the app is in.
    * @param normalizationConfig The [[AppNormalization.Config]].
    * @return a normalized and validated app.
    */
  def visitApp(
      app: raml.App,
      absoluteGroupPath: AbsolutePathId,
      normalizationConfig: AppNormalization.Config,
      enforceRole: Boolean
  ): raml.App = {
    val normalizedAbsoluteId = PathId(app.id).canonicalPath(absoluteGroupPath)
    val validRoles = validRolesFor(normalizedAbsoluteId, normalizationConfig.defaultRole, enforceRole)

    val validateAndNormalizeApp: Normalization[raml.App] =
      AppHelpers.appNormalization(normalizationConfig, validRoles)(AppNormalization.withCanonizedIds(absoluteGroupPath))
    validateAndNormalizeApp.normalized(app.copy(id = normalizedAbsoluteId.toString))
  }

  /**
    * Factory method for group update normalization of an API call.
    *
    * @param conf The [[MarathonConf]] holding the default Mesos role and the default enforce group
    *             role behavior.
    * @param groupPath The absolute path of the group being updated.
    * @param originalRootGroup The [[RootGroup]] before the update was applied.
    * @return The normalized group update.
    */
  def updateNormalization(groupPath: AbsolutePathId): Normalization[raml.GroupUpdate] =
    Normalization { update =>
      // Only update if this is not a scale or rollback
      if (update.version.isEmpty && update.scaleBy.isEmpty) {
        if (groupPath.isRoot) visitRootGroup(conf, update)
        else if (groupPath.isTopLevel) visitTopLevelGroup(conf, update, groupPath, conf.newGroupEnforceRole(), conf.mesosRole())
        else {
          val (defaultRole, enforceRole) = inferDefaultRole(conf, groupPath, originalRootGroup)
          visitChildGroup(conf, update, groupPath, defaultRole, enforceRole)
        }
      } else update
    }

  def partialUpdateNormalization(): Normalization[raml.GroupPartialUpdate] =
    Normalization { update =>
      update.copy(enforceRole = Some(effectiveEnforceRole(conf.newGroupEnforceRole(), update.enforceRole)))
    }

  /**
    * Infers the enforce role field for a top-level group based on the update value and the default behavior.
    *
    * @param groupRoleBehavior The Marathon configured [[NewGroupEnforceRoleBehavior]]
    * @param maybeEnforceRole The role defined by the update.
    * @return Whether or not to enforce the role.
    */
  private def effectiveEnforceRole(groupRoleBehavior: NewGroupEnforceRoleBehavior, maybeEnforceRole: Option[Boolean]): Boolean = {
    maybeEnforceRole.getOrElse {
      groupRoleBehavior match {
        case NewGroupEnforceRoleBehavior.Off => false
        case NewGroupEnforceRoleBehavior.Top => true
      }
    }
  }

  /**
    * Determine the default role for a lower level group update.
    *
    * @param conf The [[MarathonConf]] used to check the default Mesos role.
    * @param groupId The group id of the lower level group. Must not be root or top-level.
    * @param rootGroup The root group used to look up the default role.
    * @return a tuple of the default role for all apps and pods and whether it should be enforced.
    */
  private def inferDefaultRole(conf: MarathonConf, groupId: AbsolutePathId, rootGroup: RootGroup): (Role, Boolean) = {
    require(!groupId.isTopLevel && !groupId.isRoot)
    rootGroup.group(groupId.rootPath) match {
      case None =>
        // If the top-level does not exist it is created during the update. Thus the enforced role is defined
        // by the configured behavior.
        (conf.mesosRole(), effectiveEnforceRole(conf.newGroupEnforceRole(), None))
      case Some(topLevelGroup) =>
        val defaultRole = if (topLevelGroup.enforceRole) groupId.root else conf.mesosRole()
        (defaultRole, topLevelGroup.enforceRole)
    }
  }

  private def validRolesFor(appId: AbsolutePathId, defaultRole: Role, enforceRole: Boolean): Set[String] = {
    // Look up any previously set role on the specified runSpec, and add that to the validRoles set if it exists
    val maybeExistingRole: Option[String] = originalRootGroup.runSpec(appId).map(_.role)

    if (enforceRole) {
      Set(defaultRole) ++ maybeExistingRole
    } else {
      Set(defaultRole, conf.mesosRole()) ++ maybeExistingRole
    }
  }
}
