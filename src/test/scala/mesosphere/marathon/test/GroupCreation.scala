package mesosphere.marathon
package test

import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._
import com.wix.accord

trait GroupCreation {
  def createRootGroup(
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groups: Set[Group] = Set.empty,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion,
    validate: Boolean = true,
    enabledFeatures: Set[String] = Set.empty): RootGroup = {
    val group = RootGroup(apps, pods, groups.map(group => group.id -> group)(collection.breakOut), dependencies, version)

    if (validate) {
      val validation = accord.validate(group)(RootGroup.rootGroupValidator(enabledFeatures))
      assert(validation.isSuccess, s"Provided test root group was not valid: ${validation.toString}")
    }

    group
  }

  def createGroup(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groups: Set[Group] = Set.empty,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion,
    validate: Boolean = true,
    enabledFeatures: Set[String] = Set.empty): Group = {
    val groupsById: Map[Group.GroupKey, Group] = groups.map(group => group.id -> group)(collection.breakOut)
    val group = Group(
      id,
      apps,
      pods,
      groupsById,
      dependencies,
      version)

    if (validate) {
      val validation = accord.validate(group)(Group.validGroup(id.parent, enabledFeatures))
      assert(validation.isSuccess, s"Provided test group was not valid: ${validation.toString}")
    }

    group
  }
}
