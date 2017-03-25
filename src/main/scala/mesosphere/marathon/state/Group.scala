package mesosphere.marathon
package state

import java.util.Objects

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.{ Group => IGroup }
import mesosphere.marathon.state.Group._
import mesosphere.marathon.state.PathId._

class Group(
    val id: PathId,
    val apps: Map[AppDefinition.AppKey, AppDefinition] = defaultApps,
    val pods: Map[PathId, PodDefinition] = defaultPods,
    val groupsById: Map[Group.GroupKey, Group] = defaultGroups,
    val dependencies: Set[PathId] = defaultDependencies,
    val version: Timestamp = defaultVersion,
    val transitiveAppsById: Map[AppDefinition.AppKey, AppDefinition],
    val transitivePodsById: Map[PathId, PodDefinition]) extends IGroup {

  def app(appId: PathId): Option[AppDefinition] = transitiveAppsById.get(appId)
  def pod(podId: PathId): Option[PodDefinition] = transitivePodsById.get(podId)

  /**
    * Find and return the child group for the given path. If no match is found, then returns None
    */
  def group(gid: PathId): Option[Group] = {
    if (id == gid) Some(this)
    else {
      val immediateChild = gid.restOf(id).root
      groupsById.find { case (_, group) => group.id.restOf(id).root == immediateChild }
        .flatMap { case (_, group) => group.group(gid) }
    }
  }

  lazy val transitiveApps: Set[AppDefinition] = transitiveAppsById.values.toSet
  lazy val transitiveAppIds: Set[PathId] = transitiveAppsById.keySet

  lazy val transitiveRunSpecsById: Map[PathId, RunSpec] = transitiveAppsById ++ transitivePodsById
  lazy val transitiveRunSpecs: Set[RunSpec] = transitiveRunSpecsById.values.toSet

  lazy val transitiveGroupsById: Map[Group.GroupKey, Group] = {
    Map(id -> this) ++ groupsById.values.flatMap(_.transitiveGroupsById)
  }

  /** @return true if and only if this group directly or indirectly contains app definitions. */
  def containsApps: Boolean = apps.nonEmpty || groupsById.exists { case (_, group) => group.containsApps }

  def containsPods: Boolean = pods.nonEmpty || groupsById.exists { case (_, group) => group.containsPods }

  def containsAppsOrPodsOrGroups: Boolean = apps.nonEmpty || groupsById.nonEmpty || pods.nonEmpty

  override def equals(other: Any): Boolean = other match {
    case that: Group =>
      id == that.id &&
        apps == that.apps &&
        pods == that.pods &&
        groupsById == that.groupsById &&
        dependencies == that.dependencies &&
        version == that.version
    case _ => false
  }

  override def hashCode(): Int = Objects.hash(id, apps, pods, groupsById, dependencies, version)

  override def toString = s"Group($id, ${apps.values}, ${pods.values}, ${groupsById.values}, $dependencies, $version)"
}

object Group {
  type GroupKey = PathId

  def apply(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groupsById: Map[Group.GroupKey, Group] = Group.defaultGroups,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion,
    transitiveAppsById: Map[AppDefinition.AppKey, AppDefinition],
    transitivePodsById: Map[PathId, PodDefinition]): Group =
    new Group(id, apps, pods, groupsById, dependencies, version, transitiveAppsById, transitivePodsById)

  def empty(id: PathId): Group =
    Group(id = id, version = Timestamp(0), transitiveAppsById = Map.empty, transitivePodsById = Map.empty)

  def defaultApps: Map[AppDefinition.AppKey, AppDefinition] = Map.empty
  val defaultPods = Map.empty[PathId, PodDefinition]
  def defaultGroups: Map[Group.GroupKey, Group] = Map.empty
  def defaultDependencies: Set[PathId] = Set.empty
  def defaultVersion: Timestamp = Timestamp.now()

  def validGroup(base: PathId, enabledFeatures: Set[String]): Validator[Group] =
    validator[Group] { group =>
      group.id is validPathWithBase(base)
      group.apps.values as "apps" is every(
        AppDefinition.validNestedAppDefinition(group.id.canonicalPath(base), enabledFeatures))
      group is noAppsAndPodsWithSameId
      group is noAppsAndGroupsWithSameName
      group is noPodsAndGroupsWithSameName
      group.groupsById.values as "groups" is every(validGroup(group.id.canonicalPath(base), enabledFeatures))
    }

  private def noAppsAndPodsWithSameId: Validator[Group] =
    isTrue("Applications and Pods may not share the same id") { group =>
      val podIds = group.transitivePodsById.keySet
      val appIds = group.transitiveAppsById.keySet
      appIds.intersect(podIds).isEmpty
    }

  private def noAppsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Applications may not have the same identifier.") { group =>
      val groupIds = group.groupsById.keySet
      val clashingIds = groupIds.intersect(group.apps.keySet)
      clashingIds.isEmpty
    }

  private def noPodsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Pods may not have the same identifier.") { group =>
      val groupIds = group.groupsById.keySet
      val clashingIds = groupIds.intersect(group.pods.keySet)
      clashingIds.isEmpty
    }

  def emptyUpdate(id: PathId): raml.GroupUpdate = raml.GroupUpdate(Some(id.toString))

  /** requires that apps are in canonical form */
  def validNestedGroupUpdateWithBase(base: PathId): Validator[raml.GroupUpdate] =
    validator[raml.GroupUpdate] { group =>
      group is notNull

      group.version is theOnlyDefinedOptionIn(group)
      group.scaleBy is theOnlyDefinedOptionIn(group)

      // this is funny: id is "optional" only because version and scaleBy can't be used in conjunction with other
      // fields. it feels like we should make an exception for "id" and always require it for non-root groups.
      group.id.map(_.toPath) as "id" is optional(valid)

      group.apps is optional(every(
        AppValidation.validNestedApp(group.id.fold(base)(PathId(_).canonicalPath(base)))))
      group.groups is optional(every(
        validNestedGroupUpdateWithBase(group.id.fold(base)(PathId(_).canonicalPath(base)))))
    }
}
