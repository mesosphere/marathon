package mesosphere.marathon
package api.v2

import mesosphere.marathon.state.{PathId, RootGroup}
import mesosphere.mesos.ResourceMatcher.Role

object GroupNormalization {

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    update.copy(enforceRole = Some(effectiveEnforceRole(conf, update.enforceRole)))
  }

  def updateNormalization(conf: MarathonConf, effectivePath: PathId, originalRootGroup: RootGroup): Normalization[raml.GroupUpdate] = Normalization { update =>
    assert(!effectivePath.isRoot, "TODO: support root group update")
    val withNormalizedRoles = normalizeRoles(conf, effectivePath, update)
    val defaultRole: Role = if (effectivePath.parent.isRoot) {
      if (withNormalizedRoles.enforceRole.get) effectivePath.root
      else conf.mesosRole()
    } else {
      val enforceRole = effectiveEnforceRole(conf, originalRootGroup.group(effectivePath.parent).map(_.enforceRole))
      if (enforceRole) effectivePath.parent.root
      else conf.mesosRole()
    }
    normalizeApps(conf, withNormalizedRoles, effectivePath, defaultRole)
  }

  private def normalizeRoles(conf: MarathonConf, id: PathId, update: raml.GroupUpdate): raml.GroupUpdate = {
    // Only update if this is not a scale or rollback
    if (update.version.isEmpty && update.scaleBy.isEmpty) {
      if (id.parent.isRoot) {
        update.copy(enforceRole = Some(effectiveEnforceRole(conf, update.enforceRole)), groups = update.groups.map(normalizeChildren(conf, false)))
      } else {
        val enforceRole = update.enforceRole.orElse(Some(false))
        update.copy(enforceRole = enforceRole, groups = update.groups.map(normalizeChildren(conf, id.isRoot)))
      }
    } else update
  }

  private def normalizeApps(conf: MarathonConf, update: raml.GroupUpdate, rootPath: PathId, defaultRole: Role): raml.GroupUpdate = {
    val groupPath = update.id.map(PathId(_).canonicalPath(rootPath)).getOrElse(rootPath)
    println(s"effective $rootPath, group path $groupPath")
    val apps = update.apps.map(_.map { a =>

      val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)
      val validateAndNormalizeApp: Normalization[raml.App] = AppHelpers.appNormalization(normalizationConfig)(AppNormalization.withCanonizedIds())

      validateAndNormalizeApp.normalized(a.copy(id = PathId(a.id).canonicalPath(groupPath).toString))
    })

    val groups = update.groups.map(_.map { g =>
      normalizeApps(conf, g, groupPath, defaultRole)
    })

    update.copy(apps = apps, groups = groups)
  }

  /**
    * Infers the enforce role field for a top-level group based on the update value and the default behavior.
    *
    * @param conf The Marathon conf defining the default behavior.
    * @param maybeEnforceRole The role defined by the updated.
    * @return Whether or not to enforce the role.
    */
  private def effectiveEnforceRole(conf: MarathonConf, maybeEnforceRole: Option[Boolean]): Boolean = {
    maybeEnforceRole.getOrElse {
      conf.groupRoleBehavior() match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
    }
  }

  /**
    * Normalizes all children of the group.
    *
    * @param conf The Marathon config.
    * @param parentIsRoot Indicates whether the children have the root group as the parent or not.
    * @param childGroups This children that should be updated.
    * @return The set of updated children.
    */
  private def normalizeChildren(conf: MarathonConf, parentIsRoot: Boolean)(childGroups: Set[raml.GroupUpdate]): Set[raml.GroupUpdate] = {
    if (parentIsRoot) {
      childGroups.map { child =>
        child.copy(enforceRole = Some(effectiveEnforceRole(conf, child.enforceRole)), groups = child.groups.map(normalizeChildren(conf, false)))
      }
    } else {
      childGroups.map { child =>
        val enforceRole = child.enforceRole.orElse(Some(false))
        child.copy(enforceRole = enforceRole, groups = child.groups.map(normalizeChildren(conf, false)))
      }
    }
  }
}
