package mesosphere.marathon.api.v2

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import java.lang.{ Double => JDouble }

@JsonIgnoreProperties(ignoreUnknown = true)
case class GroupUpdate(
    id: Option[PathId],
    apps: Option[Set[AppDefinition]] = None,
    groups: Option[Set[GroupUpdate]] = None,
    dependencies: Option[Set[PathId]] = None,
    scale: Option[JDouble] = None,
    version: Option[Timestamp] = None) {

  //TODO: fallback, if no id is given
  def groupId: PathId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

  def apply(current: Group, timestamp: Timestamp): Group = {
    require(scale.isEmpty, "To apply the update, no scale should be given.")
    require(version.isEmpty, "To apply the update, no version should be given.")
    val effectiveGroups = groups.fold(current.groups) { groups =>
      val currentIds = current.groups.map(_.id)
      val groupIds = groups.map(_.groupId)
      val changedIds = currentIds.intersect(groupIds)
      val changedIdList = changedIds.toList
      val groupUpdates = changedIdList
        .flatMap(gid => current.groups.find(_.id == gid))
        .zip(changedIdList.flatMap(gid => groups.find(_.groupId == gid)))
        .map { case (group, groupUpdate) => groupUpdate(group, timestamp) }
      val groupAdditions = groupIds
        .diff(changedIds)
        .flatMap(gid => groups.find(_.groupId == gid))
        .map(_.toGroup(current.id, timestamp))
      groupUpdates.toSet ++ groupAdditions
    }
    val effectiveApps = apps.getOrElse(current.apps).map(app => app.copy(id = app.id.canonicalPath(current.id)))
    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    Group(current.id, effectiveApps, effectiveGroups, effectiveDependencies, timestamp)
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
