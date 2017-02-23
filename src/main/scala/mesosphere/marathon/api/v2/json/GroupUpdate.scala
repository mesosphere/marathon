package mesosphere.marathon
package api.v2.json

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
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
    val effectiveGroups: Map[PathId, Group] = groups.fold(current.groupsById) { updates =>
      updates.map { groupUpdate =>
        val groupId = groupUpdate.groupId.canonicalPath(current.id)
        val newGroup = current.groupsById.get(groupId).fold(groupUpdate.toGroup(groupId, timestamp))(groupUpdate(_, timestamp))
        newGroup.id -> newGroup
      }(collection.breakOut)
    }
    val effectiveApps: Map[AppDefinition.AppKey, AppDefinition] =
      apps.getOrElse(current.apps.values).map { currentApp =>
        val app = toApp(current.id, currentApp, timestamp)
        app.id -> app
      }(collection.breakOut)

    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    Group(
      id = current.id,
      apps = effectiveApps,
      pods = current.pods,
      groupsById = effectiveGroups,
      dependencies = effectiveDependencies,
      version = timestamp,
      transitiveAppsById = effectiveApps ++ effectiveGroups.values.flatMap(_.transitiveAppsById),
      transitivePodsById = current.pods ++ effectiveGroups.values.flatMap(_.transitivePodsById))
  }

  def toApp(gid: PathId, app: AppDefinition, version: Timestamp): AppDefinition = {
    val appId = app.id.canonicalPath(gid)
    app.copy(id = appId, dependencies = app.dependencies.map(_.canonicalPath(gid)),
      versionInfo = VersionInfo.OnlyVersion(version))
  }

  def toGroup(gid: PathId, version: Timestamp): Group = {
    val appsById: Map[AppDefinition.AppKey, AppDefinition] = apps.getOrElse(Set.empty).map { currentApp =>
      val app = toApp(gid, currentApp, version)
      app.id -> app
    }(collection.breakOut)
    val groupsById: Map[PathId, Group] = groups.getOrElse(Set.empty).map { currentGroup =>
      val group = currentGroup.toGroup(currentGroup.groupId.canonicalPath(gid), version)
      group.id -> group
    }(collection.breakOut)
    Group(
      id = gid,
      apps = appsById,
      pods = Map.empty,
      groupsById = groupsById,
      dependencies = dependencies.fold(Set.empty[PathId])(_.map(_.canonicalPath(gid))),
      version = version,
      transitiveAppsById = appsById ++ groupsById.values.flatMap(_.transitiveAppsById),
      transitivePodsById = Map.empty)
  }
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
