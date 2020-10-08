package mesosphere.marathon
package state

import com.typesafe.scalalogging.StrictLogging
import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.util.RoleSettings
import org.jgrapht.DirectedGraph
import org.jgrapht.alg.CycleDetector
import org.jgrapht.graph._

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
  * Represents the root group for Marathon. It is a persistent data structure,
  * and all of the modifying operations are defined at this level.
  */
class RootGroup protected (
    apps: Map[AbsolutePathId, AppDefinition],
    pods: Map[AbsolutePathId, PodDefinition],
    groupsById: Map[AbsolutePathId, Group],
    dependencies: Set[AbsolutePathId],
    val newGroupStrategy: RootGroup.NewGroupStrategy,
    version: Timestamp
) extends Group(PathId.root, apps, pods, groupsById, dependencies, version, None)
    with StrictLogging {
  require(
    groupsById.forall {
      case (_, _: RootGroup) => false
      case (_, _: Group) => true
    },
    "`RootGroup` cannot be a child of `RootGroup`."
  )

  lazy val applicationDependencies: List[(AppDefinition, AppDefinition)] = {
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
      dependentApp = this.app(dependencyId).map(Set(_))
      dependentGroup = transitiveGroupsById.get(dependencyId).map(_.transitiveApps)
      dependent <- dependentApp orElse dependentGroup getOrElse Set.empty
    } result ::= app -> dependent
    result
  }

  /**
    * This is used to compute the "layered" topological sort during deployment.
    *
    * @return The dependency graph of all the run specs in the root group.
    */
  lazy val dependencyGraph: DirectedGraph[RunSpec, DefaultEdge] = {
    val graph = new DefaultDirectedGraph[RunSpec, DefaultEdge](classOf[DefaultEdge])
    for (runnableSpec <- transitiveRunSpecs) graph.addVertex(runnableSpec)
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
    * @param version  the new version of the root group
    * @return the new root group with `newGroup` added.
    */
  def putGroup(newGroup: Group, version: Timestamp = Group.defaultVersion): RootGroup = {
    @tailrec def rebuildTree(allParents: List[AbsolutePathId], result: Group): Group = {
      allParents match {
        case Nil =>
          result
        case head :: tail =>
          val oldParent = group(head).getOrElse(newGroupStrategy.newGroup(head))
          val newParent = Group(
            id = oldParent.id,
            apps = oldParent.apps,
            pods = oldParent.pods,
            groupsById = oldParent.groupsById + (result.id -> result),
            dependencies = oldParent.dependencies,
            version = version,
            enforceRole = oldParent.enforceRole
          )
          rebuildTree(tail, newParent)
      }
    }

    updatedWith(updateVersion(rebuildTree(newGroup.id.allParents, newGroup), version))
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
  def makeGroup(groupId: AbsolutePathId): RootGroup = {
    val newGroup = newGroupStrategy.newGroup(groupId)
    group(groupId).fold(putGroup(newGroup, version))(_ => this)
  }

  /**
    * Apply an update function to every transitive app rooted at a specified group id.
    *
    * If `groupId` or any intermediate groups along the path does not exist, an empty `Group` is created.
    * This is similar to the behavior of `mkdir -p`.
    *
    * Every transitive group gets the new version.
    *
    * @param groupId the root of the group subtree to update
    * @param app     the update function, which is applied to every transitive app under `groupId`.
    * @param version the new version of the root group
    * @return the new root group with group with id `groupId`.
    */
  def updateTransitiveApps(
      groupId: AbsolutePathId,
      app: AppDefinition => AppDefinition,
      version: Timestamp = Group.defaultVersion
  ): RootGroup = {
    def updateApps(group: Group): Group = {
      Group(
        id = group.id,
        apps = group.apps.map { case (appId, appDef) => appId -> app(appDef) },
        pods = group.pods,
        groupsById = group.groupsById.map { case (subGroupId, subGroup) => subGroupId -> updateApps(subGroup) },
        dependencies = group.dependencies,
        version = version,
        enforceRole = group.enforceRole
      )
    }

    val oldGroup = group(groupId).getOrElse(newGroupStrategy.newGroup(groupId))
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
    * @param apps    the update function, which is applied to the specified group's apps.
    * @param version the new version of the root group
    * @return the new root group with the specified group updated.
    */
  def updateApps(
      groupId: AbsolutePathId,
      apps: Map[AbsolutePathId, AppDefinition] => Map[AbsolutePathId, AppDefinition],
      version: Timestamp = Group.defaultVersion
  ): RootGroup = {
    val oldGroup = group(groupId).getOrElse(newGroupStrategy.newGroup(groupId))
    val oldApps = oldGroup.apps
    val newApps = apps(oldApps)
    val newGroup = Group(
      id = oldGroup.id,
      apps = newApps,
      pods = oldGroup.pods,
      groupsById = oldGroup.groupsById,
      dependencies = oldGroup.dependencies,
      version = version,
      enforceRole = oldGroup.enforceRole
    )
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
    * @param groupId      the id of the group to be updated
    * @param dependencies the update function, which is applied to the specified group's dependencies.
    * @param version      the new version of the root group
    * @return the new root group with the specified group updated.
    */
  def updateDependencies(
      groupId: AbsolutePathId,
      dependencies: Set[AbsolutePathId] => Set[AbsolutePathId],
      version: Timestamp = Group.defaultVersion
  ): RootGroup = {
    val oldGroup = group(groupId).getOrElse(newGroupStrategy.newGroup(groupId))
    val oldDependencies = oldGroup.dependencies
    val newDependencies = dependencies(oldDependencies)
    putGroup(oldGroup.withDependencies(newDependencies), version)
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
    * @param appId   the id of the app to be updated
    * @param fn      the update function, which is applied to the specified app
    * @param version the new version of the root group
    * @return the new root group with the specified app updated.
    */
  def updateApp(appId: AbsolutePathId, fn: Option[AppDefinition] => AppDefinition, version: Timestamp = Group.defaultVersion): RootGroup = {
    val oldGroup = group(appId.parent).getOrElse(newGroupStrategy.newGroup(appId.parent))
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
      enforceRole = oldGroup.enforceRole
    )
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
    * @param podId   the id of the pod to be updated
    * @param fn      the update function, which is applied to the specified pod
    * @param version the new version of the root group
    * @return the new root group with the specified pod updated.
    */
  def updatePod(podId: AbsolutePathId, fn: Option[PodDefinition] => PodDefinition, version: Timestamp = Group.defaultVersion): RootGroup = {
    val oldGroup = group(podId.parent).getOrElse(newGroupStrategy.newGroup(podId.parent))
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
      enforceRole = oldGroup.enforceRole
    )
    putGroup(newGroup, version)
  }

  /**
    * Update a group with the specified group id by applying the update function.
    *
    * It has the same semantics as [[updateApp()]] and [[updatePod()]]. If the group does not exist
    * it will be created. The update does *not* change the group version.
    *
    * @param groupId the if od the group to be updated.
    * @param fn      the update function.
    * @return the new root group with the update group.
    */
  def updateGroup(groupId: AbsolutePathId, fn: Option[Group] => Group): RootGroup = {
    val updatedGroup = fn(group(groupId))
    putGroup(updatedGroup, updatedGroup.version)
  }

  private def updateVersion(group: Group, version: Timestamp): Group = {
    Group(
      id = group.id,
      apps = group.apps,
      pods = group.pods,
      groupsById = group.groupsById.map { case (subGroupId, subGroup) => subGroupId -> updateVersion(subGroup, version) },
      dependencies = group.dependencies,
      version = version,
      enforceRole = group.enforceRole
    )
  }

  /**
    * Returns a new `RootGroup` where all transitive groups have their `version` set to the specified timestamp.
    *
    * @param version the new version of the root group.
    */
  def updateVersion(version: Timestamp = Group.defaultVersion): RootGroup = {
    updatedWith(updateVersion(this, version))
  }

  /**
    * Remove the group with the specified group id.
    *
    * Every transitive group gets the new version.
    *
    * @param groupId the id of the group to be removed
    * @param version the new version of the root group
    * @return the new root group with the specified group removed.
    */
  def removeGroup(groupId: AbsolutePathId, version: Timestamp = Group.defaultVersion): RootGroup = {
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
          enforceRole = oldParent.enforceRole
        ),
        version
      )
    }
  }

  /**
    * Remove the app with the specified app id.
    *
    * Every transitive group gets the new version.
    *
    * @param appId   the id of the app to be removed
    * @param version the new version of the root group
    * @return the new root group with the specified app removed.
    */
  def removeApp(appId: AbsolutePathId, version: Timestamp = Group.defaultVersion): RootGroup = {
    app(appId).fold(updateVersion(version)) { oldApp =>
      val oldGroup = transitiveGroupsById(oldApp.id.parent)
      putGroup(
        Group(
          id = oldGroup.id,
          apps = oldGroup.apps - oldApp.id,
          pods = oldGroup.pods,
          groupsById = oldGroup.groupsById,
          dependencies = oldGroup.dependencies,
          version = version,
          enforceRole = oldGroup.enforceRole
        ),
        version
      )
    }
  }

  /**
    * Remove the pod with the specified pod id.
    *
    * Every transitive group gets the new version.
    *
    * @param podId   the id of the pod to be removed
    * @param version the new version of the root group
    * @return the new root group with the specified pod removed.
    */
  def removePod(podId: AbsolutePathId, version: Timestamp = Group.defaultVersion): RootGroup = {
    pod(podId).fold(updateVersion(version)) { oldPod =>
      val oldGroup = transitiveGroupsById(oldPod.id.parent)
      putGroup(
        Group(
          id = oldGroup.id,
          apps = oldGroup.apps,
          pods = oldGroup.pods - oldPod.id,
          groupsById = oldGroup.groupsById,
          dependencies = oldGroup.dependencies,
          version = version,
          enforceRole = oldGroup.enforceRole
        ),
        version
      )
    }
  }

  /**
    * Returns a new `RootGroup` where all transitive groups, apps, and pods have their `version` set to `Timestamp(0)`.
    */
  def withNormalizedVersions: RootGroup = {
    def in(group: Group): Group = {
      Group(
        id = group.id,
        apps = group.apps.map { case (appId, app) => appId -> app.copy(versionInfo = VersionInfo.NoVersion) },
        pods = group.pods.map { case (podId, pod) => podId -> pod.copy(versionInfo = VersionInfo.NoVersion) },
        groupsById = group.groupsById.map { case (subGroupId, subGroup) => subGroupId -> in(subGroup) },
        dependencies = group.dependencies,
        version = Timestamp(0),
        enforceRole = group.enforceRole
      )
    }

    updatedWith(in(this))
  }

  def updatedWith(group: Group): RootGroup = {
    RootGroup.fromGroup(group, newGroupStrategy)
  }
}

object RootGroup {
  def apply(
      apps: Map[AbsolutePathId, AppDefinition],
      pods: Map[AbsolutePathId, PodDefinition],
      groupsById: Map[AbsolutePathId, Group],
      dependencies: Set[AbsolutePathId],
      newGroupStrategy: NewGroupStrategy,
      version: Timestamp
  ): RootGroup = new RootGroup(apps, pods, groupsById, dependencies, newGroupStrategy, version)

  def empty(newGroupStrategy: NewGroupStrategy = NewGroupStrategy.Fail, version: Timestamp = Timestamp(0)) =
    RootGroup(Map.empty, Map.empty, Map.empty, Set.empty, newGroupStrategy = newGroupStrategy, version = version)

  def fromGroup(group: Group, newGroupStrategy: NewGroupStrategy): RootGroup = {
    require(group.id.isRoot)
    RootGroup(group.apps, group.pods, group.groupsById, group.dependencies, newGroupStrategy, group.version)
  }

  def validRootGroup(config: MarathonConf): Validator[RootGroup] =
    validator[RootGroup] { rootGroup =>
      rootGroup is noCyclicDependencies
      rootGroup is Group.validGroup(PathId.root, config)
      rootGroup is ExternalVolumes.validRootGroup()
      rootGroup.transitiveApps as "apps" is Group.everyApp(
        validAppRole(config, rootGroup)
      )
      rootGroup.transitivePods as "pods" is Group.everyPod(
        validPodRole(config, rootGroup)
      )
    }

  private def noCyclicDependencies: Validator[RootGroup] =
    isTrue("Dependency graph has cyclic dependencies.") {
      _.hasNonCyclicDependencies
    }

  private def validAppRole(config: MarathonConf, rootGroup: RootGroup): Validator[AppDefinition] =
    (app: AppDefinition) => {
      AppDefinition.validWithRoleEnforcement(RoleSettings.forService(config, app.id, rootGroup, false)).apply(app)
    }

  private def validPodRole(config: MarathonConf, rootGroup: RootGroup): Validator[PodDefinition] =
    (pod: PodDefinition) => {
      PodsValidation.validPodDefinitionWithRoleEnforcement(RoleSettings.forService(config, pod.id, rootGroup, false)).apply(pod)
    }

  trait NewGroupStrategy {
    def enforceRoleValue(id: AbsolutePathId): Option[Boolean]

    def newGroup(id: AbsolutePathId): Group
  }

  object NewGroupStrategy {

    object Fail extends NewGroupStrategy {
      override def enforceRoleValue(id: AbsolutePathId): Option[Boolean] = None

      override def newGroup(id: AbsolutePathId): Group = throw new RuntimeException(s"No such group: ${id}")
    }

    case class UsingConfig(newGroupEnforceRoleBehavior: NewGroupEnforceRoleBehavior) extends NewGroupStrategy {
      override def enforceRoleValue(id: AbsolutePathId): Option[Boolean] = {
        if (id.isTopLevel) {
          newGroupEnforceRoleBehavior.option
        } else {
          None
        }
      }

      override def newGroup(id: AbsolutePathId): Group =
        Group.empty(id, enforceRole = enforceRoleValue(id))
    }
  }
}
