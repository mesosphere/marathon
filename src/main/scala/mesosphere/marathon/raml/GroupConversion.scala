package mesosphere.marathon
package raml

import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, PathId, RootGroup, Timestamp, Group => CoreGroup, VersionInfo => CoreVersionInfo}

case class GroupUpdateConversionVisitor(originalRootGroup: RootGroup, timestamp: Timestamp, appConversion: App => AppDefinition) extends GroupUpdateVisitor[AppDefinition, CoreGroup] {

  override def visit(thisGroup: GroupUpdate): CoreGroup = {
    require(thisGroup.scaleBy.isEmpty, "For a structural update, no scale should be given.")
    require(thisGroup.version.isEmpty, "For a structural update, no version should be given.")
    assert(thisGroup.enforceRole.isDefined, s"BUG! The group normalization should have set enforceRole for ${thisGroup.id}.")

    val current = originalRootGroup.group(AbsolutePathId(thisGroup.id.get)).getOrElse(CoreGroup.empty(AbsolutePathId(thisGroup.id.get)))
    println(s"current: $current")
    val effectiveDependencies = thisGroup.dependencies.fold(current.dependencies)(_.map(PathId(_).canonicalPath(current.id.asAbsolutePath)))

    CoreGroup(
      id = current.id,
      apps = current.apps,
      pods = current.pods,
      groupsById = current.groupsById,
      dependencies = effectiveDependencies,
      version = timestamp,
      enforceRole = thisGroup.enforceRole.get)
  }

  override def childGroupVisitor(): GroupUpdateVisitor[AppDefinition, CoreGroup] = GroupUpdateConversionVisitor(originalRootGroup, timestamp, appConversion)

  override val appVisitor: AppVisitor[AppDefinition] = AppConversionVisitor(appConversion, timestamp)

  override def done(base: AbsolutePathId, thisGroup: CoreGroup, children: Option[Iterator[CoreGroup]], apps: Option[Iterator[AppDefinition]]): CoreGroup = {

    // Accumulate child groups.
    val effectiveGroups: Map[PathId, CoreGroup] = children.fold(thisGroup.groupsById)(_.map { childGroup =>
      childGroup.id -> childGroup
    }.toMap)

    // Accumulate apps.
    val effectiveApps: Map[AppDefinition.AppKey, AppDefinition] = apps.fold(thisGroup.apps)(_.map { app =>
      app.id -> app
    }.toMap)

    CoreGroup(
      id = base,
      apps = effectiveApps,
      pods = thisGroup.pods,
      groupsById = effectiveGroups,
      dependencies = thisGroup.dependencies,
      version = thisGroup.version,
      enforceRole = thisGroup.enforceRole)
  }
}

// TODO: convert without a function
case class AppConversionVisitor(convert: App => AppDefinition, version: Timestamp) extends AppVisitor[AppDefinition] {
  override def visit(app: App, groupId: AbsolutePathId): AppDefinition = convert(app).copy(versionInfo = CoreVersionInfo.OnlyVersion(version))
}

object GroupConversion {

  def dispatch(groupUpdate: raml.GroupUpdate, base: AbsolutePathId, visitor: GroupUpdateVisitor[AppDefinition, CoreGroup]): CoreGroup =
    GroupUpdateVisitor.dispatch(groupUpdate, base, visitor)
}

