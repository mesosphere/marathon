package mesosphere.marathon
package test

import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._
import com.wix.accord

trait GroupCreation {
  @deprecated("Prefer Builders.newRootGroup instead", since = "1.9")
  def createRootGroup(
      apps: Map[AbsolutePathId, AppDefinition] = Map.empty,
      pods: Map[AbsolutePathId, PodDefinition] = Map.empty,
      groups: Set[Group] = Set.empty,
      dependencies: Set[AbsolutePathId] = Set.empty,
      version: Timestamp = Group.defaultVersion,
      validate: Boolean = true,
      enabledFeatures: Set[String] = Set.empty,
      newGroupEnforceRole: NewGroupEnforceRoleBehavior = NewGroupEnforceRoleBehavior.Off
  ): RootGroup = {
    val group = RootGroup(
      apps,
      pods,
      groups.iterator.map(group => group.id -> group).toMap,
      dependencies,
      RootGroup.NewGroupStrategy.UsingConfig(newGroupEnforceRole),
      version
    )

    if (validate) {
      val conf =
        if (enabledFeatures.isEmpty) AllConf.withTestConfig()
        else AllConf.withTestConfig("--enable_features", enabledFeatures.mkString(","))
      conf.newGroupEnforceRole
      val validation = accord.validate(group)(RootGroup.validRootGroup(conf))
      assert(validation.isSuccess, s"Provided test root group was not valid: ${validation.toString}")
    }

    group
  }

  def createGroup(
      id: AbsolutePathId,
      apps: Map[AbsolutePathId, AppDefinition] = Map.empty,
      pods: Map[AbsolutePathId, PodDefinition] = Map.empty,
      groups: Set[Group] = Set.empty,
      dependencies: Set[AbsolutePathId] = Set.empty,
      version: Timestamp = Group.defaultVersion,
      validate: Boolean = true,
      enabledFeatures: Set[String] = Set.empty,
      enforceRole: Option[Boolean] = None
  ): Group = {
    val groupsById: Map[AbsolutePathId, Group] = groups.iterator.map(group => group.id -> group).toMap
    val group = Group(id, apps, pods, groupsById, dependencies, version, enforceRole)

    if (validate) {
      val conf =
        if (enabledFeatures.isEmpty) AllConf.withTestConfig()
        else AllConf.withTestConfig("--enable_features", enabledFeatures.mkString(","))
      val validation = accord.validate(group)(Group.validGroup(id.parent, conf))
      assert(validation.isSuccess, s"Provided test group was not valid: ${validation.toString}")
    }

    group
  }
}
