package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state._
import mesosphere.marathon.state.ScalingStrategy

case class GroupUpdate(
    id: Option[GroupId],
    scalingStrategy: ScalingStrategy,
    apps: Seq[AppDefinition] = Seq.empty,
    groups: Seq[GroupUpdate] = Seq.empty) {

  //TODO: fallback, if no id is given
  def groupId: GroupId = id.getOrElse(throw new IllegalArgumentException("No group id was given!"))

  def apply(current: Group, version: Timestamp): Group = {
    val currentIds = current.groups.map(_.id).toSet
    val groupIds = groups.map(_.groupId).toSet
    val changedIds = currentIds.intersect(groupIds)
    val changedIdList = changedIds.toList
    val groupUpdates = changedIdList
      .flatMap(gid => current.groups.find(_.id == gid))
      .zip(changedIdList.flatMap(gid => groups.find(_.groupId == gid)))
      .map{ case (group, groupUpdate) => groupUpdate(group, version) }
    val groupAdditions = groupIds
      .diff(changedIds)
      .flatMap(gid => groups.find(_.groupId == gid))
      .map(_.toGroup(version))
    Group(current.id, scalingStrategy, apps, groupUpdates ++ groupAdditions, version)
  }

  def toGroup(version: Timestamp): Group = Group(groupId, scalingStrategy, apps, groups.map(_.toGroup(version)), version)
}

object GroupUpdate {
  def empty(id: String): GroupUpdate = GroupUpdate(Some(id), ScalingStrategy(0, None))
}
