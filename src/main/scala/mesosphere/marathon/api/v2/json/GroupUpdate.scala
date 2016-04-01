package mesosphere.marathon.api.v2.json

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.state._
import mesosphere.marathon.api.v2.Validation._

import scala.reflect.ClassTag

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
    val effectiveGroups = groups.fold(current.groups) { updates =>
      val currentIds = current.groups.map(_.id)
      val groupIds = updates.map(_.groupId.canonicalPath(current.id))
      val changedIds = currentIds.intersect(groupIds)
      val changedIdList = changedIds.toList
      val groupUpdates = changedIdList
        .flatMap(gid => current.groups.find(_.id == gid))
        .zip(changedIdList.flatMap(gid => updates.find(_.groupId.canonicalPath(current.id) == gid)))
        .map { case (group, groupUpdate) => groupUpdate(group, timestamp) }
      val groupAdditions = groupIds
        .diff(changedIds)
        .flatMap(gid => updates.find(_.groupId.canonicalPath(current.id) == gid))
        .map(update => update.toGroup(update.groupId.canonicalPath(current.id), timestamp))
      groupUpdates.toSet ++ groupAdditions
    }
    val effectiveApps: Set[AppDefinition] = apps.getOrElse(current.apps).map(toApp(current.id, _, timestamp))
    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    Group(current.id, effectiveApps, effectiveGroups, effectiveDependencies, timestamp)
  }

  def toApp(gid: PathId, app: AppDefinition, version: Timestamp): AppDefinition = {
    val appId = app.id.canonicalPath(gid)
    app.copy(id = appId, dependencies = app.dependencies.map(_.canonicalPath(gid)),
      versionInfo = AppDefinition.VersionInfo.OnlyVersion(version))
  }

  def toGroup(gid: PathId, version: Timestamp): Group = Group(
    gid,
    apps.getOrElse(Set.empty).map(toApp(gid, _, version)),
    groups.getOrElse(Set.empty).map(sub => sub.toGroup(sub.groupId.canonicalPath(gid), version)),
    dependencies.fold(Set.empty[PathId])(_.map(_.canonicalPath(gid))),
    version
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

  def validNestedGroupUpdateWithBase(base: PathId): Validator[GroupUpdate] = validator[GroupUpdate] { group =>
    group is notNull

    group.version is theOnlyDefinedOptionIn(group)
    group.scaleBy is theOnlyDefinedOptionIn(group)

    group.id is valid
    group.apps is optional(every(AppDefinition.validNestedAppDefinition(group.id.fold(base)(_.canonicalPath(base)))))
    group.groups is optional(every(validNestedGroupUpdateWithBase(group.id.fold(base)(_.canonicalPath(base)))))
  }

  implicit lazy val groupUpdateValid: Validator[GroupUpdate] = validNestedGroupUpdateWithBase(PathId.empty)
}
