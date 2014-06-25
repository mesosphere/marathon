package mesosphere.marathon.api.v2

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._

@JsonIgnoreProperties(ignoreUnknown = true)
case class GroupUpdate(
    id: Option[PathId],
    apps: Option[Set[AppDefinition]] = None,
    groups: Option[Set[GroupUpdate]] = None,
    dependencies: Option[Set[PathId]] = None) {

  //TODO: fallback, if no id is given
  def groupId: PathId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

  def apply(current: Group, version: Timestamp): Group = {
    val effectiveGroups = groups.fold(current.groups) { groups =>
      val currentIds = current.groups.map(_.id)
      val groupIds = groups.map(_.groupId)
      val changedIds = currentIds.intersect(groupIds)
      val changedIdList = changedIds.toList
      val groupUpdates = changedIdList
        .flatMap(gid => current.groups.find(_.id == gid))
        .zip(changedIdList.flatMap(gid => groups.find(_.groupId == gid)))
        .map { case (group, groupUpdate) => groupUpdate(group, version) }
      val groupAdditions = groupIds
        .diff(changedIds)
        .flatMap(gid => groups.find(_.groupId == gid))
        .map(_.toGroup(current.id, version))
      groupUpdates.toSet ++ groupAdditions
    }
    val effectiveApps = apps.getOrElse(current.apps).map(app => app.copy(id = app.id.canonicalPath(current.id)))
    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    Group(current.id, effectiveApps, effectiveGroups, effectiveDependencies, version)
  }

  def toGroup(base: PathId, version: Timestamp): Group = Group(
    groupId.canonicalPath(base),
    apps.getOrElse(Set.empty).map(app => app.copy(id = app.id.canonicalPath(base))),
    groups.getOrElse(Set.empty).map(_.toGroup(base, version)),
    dependencies.fold(Set.empty[PathId])(_.map(_.canonicalPath(base))),
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
  def empty(id: String): GroupUpdate = GroupUpdate(Some(id.toPath))
}
