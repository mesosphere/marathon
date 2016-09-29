package mesosphere.marathon.api.v2.json

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp, VersionInfo }

case class GroupUpdate(
    id: Option[PathId],
    apps: Option[Set[AppDefinition]] = None,
    groups: Option[Set[GroupUpdate]] = None,
    dependencies: Option[Set[PathId]] = None,
    scaleBy: Option[Double] = None,
    version: Option[Timestamp] = None) {

  def groupId: PathId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

  def apply(current: Group, timestamp: Timestamp): Group = {
    require(scaleBy.isEmpty, "To apply the update, no scale should be given.")
    require(version.isEmpty, "To apply the update, no version should be given.")
    val effectiveGroups = groups.fold(current.groupsById) { updates =>
      val currentIds = current.groupIds
      val groupIds = updates.map(_.groupId.canonicalPath(current.id))
      val changedIds = currentIds.intersect(groupIds)
      val changedIdList = changedIds.toList
      val groupUpdates = changedIdList
        .flatMap(gid => current.groupsById.get(gid))
        .zip(changedIdList.flatMap(gid => updates.find(_.groupId.canonicalPath(current.id) == gid)))
        .map { case (group, groupUpdate) => groupUpdate(group, timestamp) }
      val groupAdditions = groupIds
        .diff(changedIds)
        .flatMap(gid => updates.find(_.groupId.canonicalPath(current.id) == gid))
        .map(update => update.toGroup(update.groupId.canonicalPath(current.id), timestamp))
      (groupUpdates.toSet ++ groupAdditions).map(group => group.id -> group).toMap
    }
    val effectiveApps: Map[AppDefinition.AppKey, AppDefinition] =
      apps.getOrElse(current.apps.values).map { currentApp =>
        val app = toApp(current.id, currentApp, timestamp)
        app.id -> app
      }(collection.breakOut)

    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    Group(current.id, effectiveApps, current.pods, effectiveGroups, effectiveDependencies, timestamp)
  }

  def toApp(gid: PathId, app: AppDefinition, version: Timestamp): AppDefinition = {
    val appId = app.id.canonicalPath(gid)
    app.copy(id = appId, dependencies = app.dependencies.map(_.canonicalPath(gid)),
      versionInfo = VersionInfo.OnlyVersion(version))
  }

  def toGroup(gid: PathId, version: Timestamp): Group = Group(
    id = gid,
    apps = apps.getOrElse(Set.empty).map { currentApp =>
      val app = toApp(gid, currentApp, version)
      app.id -> app
    }(collection.breakOut),
    Map.empty[PathId, PodDefinition],
    groups = groups.getOrElse(Set.empty)
      .map(sub => sub.toGroup(sub.groupId.canonicalPath(gid), version))(collection.breakOut),
    dependencies = dependencies.fold(Set.empty[PathId])(_.map(_.canonicalPath(gid))),
    version = version
  )
}

object GroupUpdate {
  def apply(id: PathId, apps: Set[AppDefinition]): GroupUpdate = {
    GroupUpdate(Some(id), if (apps.isEmpty) None else Some(apps))
  }
  def apply(id: PathId, apps: Set[AppDefinition], groups: Set[GroupUpdate]): GroupUpdate = {
    GroupUpdate(Some(id), if (apps.isEmpty) None else Some(apps), if (groups.isEmpty) None else Some(groups))
  }
  def empty(id: PathId): GroupUpdate = GroupUpdate(Some(id))

  def validNestedGroupUpdateWithBase(base: PathId, enabledFeatures: Set[String]): Validator[GroupUpdate] =
    validator[GroupUpdate] { group =>
      group is notNull

      group.version is theOnlyDefinedOptionIn(group)
      group.scaleBy is theOnlyDefinedOptionIn(group)

      group.id is valid
      group.apps is optional(every(
        AppDefinition.validNestedAppDefinition(group.id.fold(base)(_.canonicalPath(base)), enabledFeatures)))
      group.groups is optional(every(
        validNestedGroupUpdateWithBase(group.id.fold(base)(_.canonicalPath(base)), enabledFeatures)))
    }

  def groupUpdateValid(enabledFeatures: Set[String]): Validator[GroupUpdate] =
    validNestedGroupUpdateWithBase(PathId.empty, enabledFeatures)
}
