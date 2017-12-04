package mesosphere.marathon
package test

import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._

trait GroupCreation {
  def createRootGroup(
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groups: Set[Group] = Set.empty,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion): RootGroup = {
    RootGroup(apps, pods, groups.map(group => group.id -> group)(collection.breakOut), dependencies, version)
  }

  def createGroup(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groups: Set[Group] = Set.empty,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion): Group = {
    val groupsById: Map[Group.GroupKey, Group] = groups.map(group => group.id -> group)(collection.breakOut)
    Group(
      id,
      apps,
      pods,
      groupsById,
      dependencies,
      version)
  }
}
