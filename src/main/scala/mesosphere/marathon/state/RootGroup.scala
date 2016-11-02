package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.pod.PodDefinition
import org.jgrapht.DirectedGraph
import org.jgrapht.alg.CycleDetector
import org.jgrapht.graph._

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
  * Represents the root group for Marathon. It is a persistent data structure,
  * and all of the modifying operations are defined at this level.
  */
class RootGroup(
  apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
  pods: Map[PathId, PodDefinition] = Group.defaultPods,
  groupsById: Map[Group.GroupKey, Group] = Group.defaultGroups,
  dependencies: Set[PathId] = Group.defaultDependencies,
  version: Timestamp = Group.defaultVersion) extends Group(
  PathId.empty,
  apps,
  pods,
  groupsById,
  dependencies,
  version,
  apps ++ groupsById.values.flatMap(_.transitiveAppsById),
  pods ++ groupsById.values.flatMap(_.transitivePodsById)) {
  require(
    groupsById.forall {
      case (_, _: RootGroup) => false
      case (_, _: Group) => true
    },
    "`RootGroup` cannot be a child of `RootGroup`.")

  private lazy val applicationDependencies: List[(AppDefinition, AppDefinition)] = {
    var result = List.empty[(AppDefinition, AppDefinition)]

    //group->group dependencies
    for {
      group <- transitiveGroupsById.values
      dependencyId <- group.dependencies
      dependency <- transitiveGroupsById.get(dependencyId)
      app <- group.transitiveApps
      dependentApp <- dependency.transitiveApps
    } result ::= app -> dependentApp

    //app->group/app dependencies
    for {
      group <- transitiveGroupsById.values.filter(_.apps.nonEmpty)
      app <- group.apps.values
      dependencyId <- app.dependencies
      dependentApp = transitiveAppsById.get(dependencyId).map(Set(_))
      dependentGroup = transitiveGroupsById.get(dependencyId).map(_.transitiveApps)
      dependent <- dependentApp orElse dependentGroup getOrElse Set.empty
    } result ::= app -> dependent
    result
  }

  /**
    * This is used to compute the "layered" topological sort during deployment.
    * @return The dependency graph of all the run specs in the root group.
    */
  lazy val dependencyGraph: DirectedGraph[RunSpec, DefaultEdge] = {
    val graph = new DefaultDirectedGraph[RunSpec, DefaultEdge](classOf[DefaultEdge])
    for (runnableSpec <- transitiveRunSpecsById.values) graph.addVertex(runnableSpec)
    for ((app, dependent) <- applicationDependencies) graph.addEdge(app, dependent)
    new UnmodifiableDirectedGraph(graph)
  }

  private[state] def runSpecsWithNoDependencies: Set[RunSpec] = {
    val g = dependencyGraph
    g.vertexSet.asScala.filter { v => g.outDegreeOf(v) == 0 }.toSet
  }

  private[state] def hasNonCyclicDependencies: Boolean = {
    !new CycleDetector[RunSpec, DefaultEdge](dependencyGraph).detectCycles()
  }

  /**
    * Add a new `Group` to the root group.
    *
    * If `newGroup.id` already exists in the root group, it will be replaced with `newGroup`.
    * This behavior is "add-or-replace", similar to `map + (key -> value)`.
    *
    * Otherwise, if any intermediate groups along the path does not exist, an empty `Group` is created.
    * For example, if a group with id `/foo/bar/baz` is being added to a root group with no children,
    * intermediate groups `/foo` and `/foo/bar` will be created in an empty state.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param newGroup the new group to be added
    * @param version the new version of the root group
    * @return the new root group with `newGroup` added.
    */
  def putGroup(newGroup: Group, version: Timestamp): RootGroup = {
    val oldGroup = group(newGroup.id).getOrElse(Group.empty(newGroup.id))
    @tailrec def rebuildTree(allParents: List[PathId], result: Group): Group = {
      allParents match {
        case Nil => result
        case head :: tail =>
          val oldParent = group(head).getOrElse(Group.empty(head))
          val newParent = Group(
            id = oldParent.id,
            apps = oldParent.apps,
            pods = oldParent.pods,
            groupsById = oldParent.groupsById + (result.id -> result),
            dependencies = oldParent.dependencies,
            version = version,
            transitiveAppsById = oldParent.transitiveAppsById -- oldGroup.apps.keys ++ newGroup.apps,
            transitivePodsById = oldParent.transitivePodsById -- oldGroup.pods.keys ++ newGroup.pods)
          rebuildTree(tail, newParent)
      }
    }
    RootGroup.fromGroup(updateVersion(rebuildTree(newGroup.id.allParents, newGroup), version))
  }

  /**
    * Make a group with the specified id exist in the root group.
    *
    * If `groupId` already exists in the root group, do nothing.
    * Otherwise, if any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * The root group's version is preserved.
    *
    * @param groupId the id of the group to make exist
    * @return the new root group with group with id `groupId`.
    */
  def makeGroup(groupId: PathId): RootGroup =
    group(groupId).fold(putGroup(Group.empty(groupId), version))(_ => this)

  /**
    * Apply an update function to every transitive app rooted at a specified group id.
    *
    * If `groupId` or any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param groupId the root of the group subtree to update
    * @param app the update function, which is applied to every transitive app under `groupId`.
    * @param version the new version of the root group
    * @return the new root group with group with id `groupId`.
    */
  def updateTransitiveApps(groupId: PathId, app: AppDefinition => AppDefinition, version: Timestamp): RootGroup = {
    def updateApps(group: Group): Group = {
      Group(
        id = group.id,
        apps = group.apps.map { case (appId, appDef) => appId -> app(appDef) },
        pods = group.pods,
        groupsById = group.groupsById.map { case (subGroupId, subGroup) => subGroupId -> updateApps(subGroup) },
        dependencies = group.dependencies,
        version = version,
        transitiveAppsById = group.transitiveAppsById.map { case (appId, appDef) => appId -> app(appDef) },
        transitivePodsById = group.transitivePodsById)
    }
    val oldGroup = group(groupId).getOrElse(Group.empty(groupId))
    val newGroup = updateApps(oldGroup)
    putGroup(newGroup, version)
  }

  /**
    * Update the apps of the group with the specified group id by applying the update function.
    *
    * If `groupId` or any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param groupId the id of the group to be updated
    * @param apps the update function, which is applied to the specified group's apps.
    * @param version the new version of the root group
    * @return the new root group with the specified group updated.
    */
  def updateApps(
    groupId: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] => Map[AppDefinition.AppKey, AppDefinition],
    version: Timestamp): RootGroup = {
    val oldGroup = group(groupId).getOrElse(Group.empty(groupId))
    val oldApps = oldGroup.apps
    val newApps = apps(oldApps)
    val newGroup = Group(
      id = oldGroup.id,
      apps = newApps,
      pods = oldGroup.pods,
      groupsById = oldGroup.groupsById,
      dependencies = oldGroup.dependencies,
      version = version,
      transitiveAppsById = oldGroup.transitiveAppsById -- oldApps.keys ++ newApps,
      transitivePodsById = oldGroup.transitivePodsById)
    putGroup(newGroup, version)
  }

  /**
    * Update the dependencies of the group with the specified group id by applying the update function.
    *
    * If `groupId` or any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param groupId the id of the group to be updated
    * @param dependencies the update function, which is applied to the specified group's dependencies.
    * @param version the new version of the root group
    * @return the new root group with the specified group updated.
    */
  def updateDependencies(groupId: PathId, dependencies: Set[PathId] => Set[PathId], version: Timestamp): RootGroup = {
    val oldGroup = group(groupId).getOrElse(Group.empty(groupId))
    val oldDependencies = oldGroup.dependencies
    val newDependencies = dependencies(oldDependencies)
    val newGroup = Group(
      id = oldGroup.id,
      apps = oldGroup.apps,
      pods = oldGroup.pods,
      groupsById = oldGroup.groupsById,
      dependencies = newDependencies,
      version = version,
      transitiveAppsById = oldGroup.transitiveAppsById,
      transitivePodsById = oldGroup.transitivePodsById
    )
    putGroup(newGroup, version)
  }

  /**
    * Update the app with the specified app id by applying the update function.
    *
    * `fn` is invoked with the app with `appId` if one exists, otherwise it is invoked with `None`.
    *
    * If `appId.parent` or any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param appId the id of the app to be updated
    * @param fn the update function, which is applied to the specified app
    * @param version the new version of the root group
    * @return the new root group with the specified app updated.
    */
  def updateApp(appId: PathId, fn: Option[AppDefinition] => AppDefinition, version: Timestamp): RootGroup = {
    val oldGroup = group(appId.parent).getOrElse(Group.empty(appId.parent))
    val newApp = fn(app(appId))
    require(newApp.id == appId, "app id must not be changed by `fn`.")
    val newGroup = Group(
      id = oldGroup.id,
      // replace potentially existing app definition
      apps = oldGroup.apps + (newApp.id -> newApp),
      pods = oldGroup.pods,
      // If there is a group with a conflicting id which contains no app or pod definitions,
      // replace it. Otherwise do not replace it. Validation should catch conflicting app/pod/group IDs later.
      groupsById = oldGroup.groupsById.filter {
        case (_, group) => group.id != newApp.id || group.containsApps || group.containsPods
      },
      dependencies = oldGroup.dependencies,
      version = version,
      transitiveAppsById = oldGroup.transitiveAppsById + (newApp.id -> newApp),
      transitivePodsById = oldGroup.transitivePodsById)
    putGroup(newGroup, version)
  }

  /**
    * Update the pod with the specified pod id by applying the update function.
    *
    * `fn` is invoked with the pod with `podId` if one exists, otherwise it is invoked with `None`.
    *
    * If `podId.parent` or any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param podId the id of the pod to be updated
    * @param fn the update function, which is applied to the specified pod
    * @param version the new version of the root group
    * @return the new root group with the specified pod updated.
    */
  def updatePod(podId: PathId, fn: Option[PodDefinition] => PodDefinition, version: Timestamp): RootGroup = {
    val oldGroup = group(podId.parent).getOrElse(Group.empty(podId.parent))
    val newPod = fn(pod(podId))
    require(newPod.id == podId, "pod id must not be changed by `fn`.")
    val newGroup = Group(
      id = oldGroup.id,
      apps = oldGroup.apps,
      // replace potentially existing pod definition
      pods = oldGroup.pods + (newPod.id -> newPod),
      // If there is a group with a conflicting id which contains no app or pod definitions,
      // replace it. Otherwise do not replace it. Validation should catch conflicting app/pod/group IDs later.
      groupsById = oldGroup.groupsById.filter {
        case (_, group) => group.id != newPod.id || group.containsApps || group.containsPods
      },
      dependencies = oldGroup.dependencies,
      version = version,
      transitiveAppsById = oldGroup.transitiveAppsById,
      transitivePodsById = oldGroup.transitivePodsById + (newPod.id -> newPod))
    putGroup(newGroup, version)
  }

  private def updateVersion(group: Group, version: Timestamp): Group = {
    Group(
      id = group.id,
      apps = group.apps,
      pods = group.pods,
      groupsById = group.groupsById.map { case (subGroupId, subGroup) => subGroupId -> updateVersion(subGroup, version) },
      dependencies = group.dependencies,
      version = version,
      transitiveAppsById = group.transitiveAppsById,
      transitivePodsById = group.transitivePodsById)
  }

  /**
    * Returns a new `RootGroup` where all transitive groups have their `version` set to the specified timestamp.
    *
    * @param version the new version of the root group.
    */
  def updateVersion(version: Timestamp): RootGroup = RootGroup.fromGroup(updateVersion(this, version))

  /**
    * Remove the group with the specified group id.
    *
    * Every transitive group gets the new version.
    *
    * @param groupId the id of the group to be removed
    * @param version the new version of the root group
    * @return the new root group with the specified group removed.
    */
  def removeGroup(groupId: PathId, version: Timestamp = Timestamp.now()): RootGroup = {
    require(!groupId.isRoot, "The root group cannot be removed.")
    group(groupId).fold(updateVersion(version)) { oldGroup =>
      val oldParent = transitiveGroupsById(oldGroup.id.parent)
      putGroup(
        Group(
          id = oldParent.id,
          apps = oldParent.apps,
          pods = oldParent.pods,
          groupsById = oldParent.groupsById - oldGroup.id,
          dependencies = oldParent.dependencies,
          version = version,
          transitiveAppsById = oldParent.transitiveAppsById -- oldGroup.transitiveAppsById.keys,
          transitivePodsById = oldParent.transitivePodsById -- oldGroup.transitivePodsById.keys), version)
    }
  }

  /**
    * Remove the app with the specified app id.
    *
    * Every transitive group gets the new version.
    *
    * @param appId the id of the app to be removed
    * @param version the new version of the root group
    * @return the new root group with the specified app removed.
    */
  def removeApp(appId: PathId, version: Timestamp = Timestamp.now()): RootGroup = {
    app(appId).fold(updateVersion(version)) { oldApp =>
      val oldGroup = transitiveGroupsById(oldApp.id.parent)
      putGroup(Group(
        id = oldGroup.id,
        apps = oldGroup.apps - oldApp.id,
        pods = oldGroup.pods,
        groupsById = oldGroup.groupsById,
        dependencies = oldGroup.dependencies,
        version = version,
        transitiveAppsById = oldGroup.transitiveAppsById - oldApp.id,
        transitivePodsById = oldGroup.transitivePodsById), version)
    }
  }

  /**
    * Remove the pod with the specified pod id.
    *
    * Every transitive group gets the new version.
    *
    * @param podId the id of the pod to be removed
    * @param version the new version of the root group
    * @return the new root group with the specified pod removed.
    */
  def removePod(podId: PathId, version: Timestamp = Timestamp.now()): RootGroup = {
    pod(podId).fold(updateVersion(version)) { oldPod =>
      val oldGroup = transitiveGroupsById(oldPod.id.parent)
      putGroup(Group(
        id = oldGroup.id,
        apps = oldGroup.apps,
        pods = oldGroup.pods - oldPod.id,
        groupsById = oldGroup.groupsById,
        dependencies = oldGroup.dependencies,
        version = version,
        transitiveAppsById = oldGroup.transitiveAppsById,
        transitivePodsById = oldGroup.transitivePodsById - oldPod.id), version)
    }
  }

  /**
    * Returns a new `RootGroup` where all transitive groups, apps, and pods have their `version` set to `Timestamp(0)`.
    */
  @SuppressWarnings(Array("PartialFunctionInsteadOfMatch"))
  def withNormalizedVersions: RootGroup = {
    def in(group: Group): Group = {
      Group(
        id = group.id,
        apps = group.apps.map { case (appId, app) => appId -> app.copy(versionInfo = VersionInfo.NoVersion) },
        pods = group.pods.map { case (podId, pod) => podId -> pod.copy(version = Timestamp(0)) },
        groupsById = group.groupsById.map { case (subGroupId, subGroup) => subGroupId -> in(subGroup) },
        dependencies = group.dependencies,
        version = Timestamp(0),
        transitiveAppsById = group.transitiveAppsById.map { case (appId, app) => appId -> app.copy(versionInfo = VersionInfo.NoVersion) },
        transitivePodsById = group.transitivePodsById.map { case (podId, pod) => podId -> pod.copy(version = Timestamp(0)) })
    }
    RootGroup.fromGroup(in(this))
  }
}

object RootGroup {
  def apply(
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groupsById: Map[Group.GroupKey, Group] = Group.defaultGroups,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion): RootGroup = new RootGroup(apps, pods, groupsById, dependencies, version)

  def empty: RootGroup = RootGroup(version = Timestamp(0))

  def fromGroup(group: Group): RootGroup = {
    require(group.id.isRoot)
    RootGroup(group.apps, group.pods, group.groupsById, group.dependencies, group.version)
  }

  def rootGroupValidator(enabledFeatures: Set[String]): Validator[RootGroup] = {
    noCyclicDependencies and
      Group.validGroup(PathId.empty, enabledFeatures) and
      ExternalVolumes.validRootGroup()
  }

  private def noCyclicDependencies: Validator[RootGroup] =
    isTrue("Dependency graph has cyclic dependencies.") { _.hasNonCyclicDependencies }
}
