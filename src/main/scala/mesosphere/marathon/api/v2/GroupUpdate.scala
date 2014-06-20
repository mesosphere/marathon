package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state._
import mesosphere.marathon.state.ScalingStrategy
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
case class GroupUpdate(
    id: Option[GroupId],
    scalingStrategy: Option[ScalingStrategy] = None,
    apps: Option[Set[AppDefinition]] = None,
    groups: Option[Set[GroupUpdate]] = None,
    dependencies: Option[Set[GroupId]] = None) {

  //TODO: fallback, if no id is given
  def groupId: GroupId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

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
    val effectiveApps = apps.getOrElse(current.apps)
    val effectiveScaling = scalingStrategy.getOrElse(current.scalingStrategy)
    val effectiveDependencies = dependencies.fold(current.dependencies)(_.map(_.canonicalPath(current.id)))
    Group(current.id, effectiveScaling, effectiveApps, effectiveGroups, effectiveDependencies, version)
  }

  def toGroup(base: GroupId, version: Timestamp): Group = Group(
    groupId.canonicalPath(base),
    scalingStrategy.getOrElse(ScalingStrategy.empty),
    apps.getOrElse(Set.empty),
    groups.getOrElse(Set.empty).map(_.toGroup(base, version)),
    dependencies.fold(Set.empty[GroupId])(_.map(_.canonicalPath(base))),
    version
  )
}

object GroupUpdate {
  def apply(id: GroupId, scalingStrategy: ScalingStrategy, apps: Set[AppDefinition]): GroupUpdate = {
    GroupUpdate(Some(id), Some(scalingStrategy), if (apps.isEmpty) None else Some(apps))
  }
  def apply(id: GroupId, scalingStrategy: ScalingStrategy, apps: Set[AppDefinition], groups: Set[GroupUpdate]): GroupUpdate = {
    GroupUpdate(Some(id), Some(scalingStrategy), if (apps.isEmpty) None else Some(apps), if (groups.isEmpty) None else Some(groups))
  }
  def empty(id: String): GroupUpdate = GroupUpdate(Some(id))
}
