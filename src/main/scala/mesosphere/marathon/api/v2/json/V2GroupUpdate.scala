package mesosphere.marathon.api.v2.json

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.state._
import java.lang.{ Double => JDouble }

@JsonIgnoreProperties(ignoreUnknown = true)
case class V2GroupUpdate(
    id: Option[PathId],
    apps: Option[Set[V2AppDefinition]] = None,
    groups: Option[Set[V2GroupUpdate]] = None,
    dependencies: Option[Set[PathId]] = None,
    scaleBy: Option[Double] = None,
    version: Option[Timestamp] = None) {

  def groupId: PathId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

  def apply(current: V2Group, timestamp: Timestamp): V2Group = {
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
    val effectiveApps: Set[V2AppDefinition] = apps.getOrElse(current.apps).map(toApp(current.id, _, timestamp))
    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    V2Group(current.id, effectiveApps, effectiveGroups, effectiveDependencies, timestamp)
  }

  def toApp(gid: PathId, app: V2AppDefinition, version: Timestamp): V2AppDefinition = {
    val appId = app.id.canonicalPath(gid)
    app.copy(id = appId, dependencies = app.dependencies.map(_.canonicalPath(appId)), version = version)
  }

  def toGroup(gid: PathId, version: Timestamp): V2Group = V2Group(
    gid,
    apps.getOrElse(Set.empty).map(toApp(gid, _, version)),
    groups.getOrElse(Set.empty).map(sub => sub.toGroup(sub.groupId.canonicalPath(gid), version)),
    dependencies.fold(Set.empty[PathId])(_.map(_.canonicalPath(gid))),
    version
  )
}

object V2GroupUpdate {
  def apply(id: PathId, apps: Set[V2AppDefinition]): V2GroupUpdate = {
    V2GroupUpdate(Some(id), if (apps.isEmpty) None else Some(apps))
  }
  def apply(id: PathId, apps: Set[V2AppDefinition], groups: Set[V2GroupUpdate]): V2GroupUpdate = {
    V2GroupUpdate(Some(id), if (apps.isEmpty) None else Some(apps), if (groups.isEmpty) None else Some(groups))
  }
  def empty(id: PathId): V2GroupUpdate = V2GroupUpdate(Some(id))
}
