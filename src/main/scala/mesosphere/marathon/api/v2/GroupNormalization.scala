package mesosphere.marathon
package api.v2

import mesosphere.marathon.raml.GroupUpdate
import mesosphere.marathon.state.{PathId, RootGroup}
import mesosphere.mesos.ResourceMatcher.Role

trait GroupUpdateVisitor {
  def visit(thisGroup: raml.GroupUpdate): raml.GroupUpdate

  def childGroupVisitor(): GroupUpdateVisitor

  def appVisitor(): AppVisitor
}

case class RootGroupVisitor(conf: MarathonConf) extends GroupUpdateVisitor {

  override def visit(thisGroup: GroupUpdate): GroupUpdate = thisGroup.copy(enforceRole = Some(false))

  override def childGroupVisitor(): GroupUpdateVisitor = TopLevelGroupVisitor(conf)

  override def appVisitor(): AppVisitor = AppNormalizeVisitor(conf, conf.mesosRole())
}

case class TopLevelGroupVisitor(conf: MarathonConf) extends GroupUpdateVisitor {
  var defaultRole: Role = conf.mesosRole()

  override def visit(thisGroup: raml.GroupUpdate): raml.GroupUpdate = {
    val enforceRole = thisGroup.enforceRole.getOrElse {
      conf.groupRoleBehavior() match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
    }
    if (enforceRole) defaultRole = PathId(thisGroup.id.get).root

    thisGroup.copy(enforceRole = Some(enforceRole))
  }

  override def childGroupVisitor(): GroupUpdateVisitor = ChildGroupVisitor(conf, defaultRole)

  override def appVisitor(): AppVisitor = AppNormalizeVisitor(conf, defaultRole)
}

case class ChildGroupVisitor(conf: MarathonConf, defaultRole: Role) extends GroupUpdateVisitor {

  override def visit(thisGroup: GroupUpdate): GroupUpdate = thisGroup.copy(enforceRole = Some(false))

  override def childGroupVisitor(): GroupUpdateVisitor = ChildGroupVisitor(conf, defaultRole)

  override def appVisitor(): AppVisitor = AppNormalizeVisitor(conf, defaultRole)
}

trait AppVisitor {
  def visit(app: raml.App, groupId: PathId): raml.App
}

case class AppNormalizeVisitor(conf: MarathonConf, defaultRole: Role) extends AppVisitor {

  val normalizationConfig = AppNormalization.Configuration(conf, defaultRole)

  // TODO: should this be the absolute group path? I think so.
  override def visit(app: raml.App, absoluteGroupPath: PathId): raml.App = {
    val validateAndNormalizeApp: Normalization[raml.App] = AppHelpers.appNormalization(normalizationConfig)(AppNormalization.withCanonizedIds())
    validateAndNormalizeApp.normalized(app.copy(id = PathId(app.id).canonicalPath(absoluteGroupPath).toString))
  }
}

object GroupNormalization {

  def dispatch(conf: MarathonConf, groupUpdate: raml.GroupUpdate, base: PathId, visitor: GroupUpdateVisitor): raml.GroupUpdate = {
    val updatedGroup = visitor.visit(groupUpdate)

    // Visit each child group.
    val childGroupVisitor = visitor.childGroupVisitor()
    val children = groupUpdate.groups.map(_.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      dispatch(conf, childGroup, absoluteChildGroupPath, childGroupVisitor)
    })

    // Visit each app.
    val appVisitor = visitor.appVisitor()
    val apps = groupUpdate.apps.map(_.map { app =>
      appVisitor.visit(app, base)
    })

    updatedGroup.copy(groups = children, apps = apps)
  }

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    update.copy(enforceRole = Some(effectiveEnforceRole(conf, update.enforceRole)))
  }

  def updateNormalization(conf: MarathonConf, base: PathId, originalRootGroup: RootGroup): Normalization[raml.GroupUpdate] = Normalization { update =>
    // Only update if this is not a scale or rollback
    if (update.version.isEmpty && update.scaleBy.isEmpty) {
      if (base.isRoot) dispatch(conf, update, base, RootGroupVisitor(conf))
      else if (base.parent.isRoot) dispatch(conf, update, base, TopLevelGroupVisitor(conf))
      else dispatch(conf, update, base, ChildGroupVisitor(conf, ???))
    } else update
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
}
