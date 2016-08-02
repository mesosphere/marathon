package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.plugin.{ Group => IGroup }
import mesosphere.marathon.Protos.GroupDefinition
import mesosphere.marathon.state.Group._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import org.jgrapht.DirectedGraph
import org.jgrapht.alg.CycleDetector
import org.jgrapht.graph._

import scala.collection.JavaConversions._

case class Group(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] = defaultApps,
    groups: Set[Group] = defaultGroups,
    dependencies: Set[PathId] = defaultDependencies,
    version: Timestamp = defaultVersion) extends MarathonState[GroupDefinition, Group] with IGroup {

  override def mergeFromProto(msg: GroupDefinition): Group = Group.fromProto(msg)
  override def mergeFromProto(bytes: Array[Byte]): Group = Group.fromProto(GroupDefinition.parseFrom(bytes))

  override def toProto: GroupDefinition = {
    import collection.JavaConverters._
    GroupDefinition.newBuilder
      .setId(id.toString)
      .setVersion(version.toString)
      .addAllApps(apps.values.map(_.toProto).asJava)
      .addAllGroups(groups.map(_.toProto))
      .addAllDependencies(dependencies.map(_.toString))
      .build()
  }

  def findGroup(fn: Group => Boolean): Option[Group] = {
    def in(groups: List[Group]): Option[Group] = groups match {
      case head :: rest => if (fn(head)) Some(head) else in(rest).orElse(in(head.groups.toList))
      case Nil => None
    }
    if (fn(this)) Some(this) else in(groups.toList)
  }

  def app(appId: PathId): Option[AppDefinition] = group(appId.parent).flatMap(_.apps.get(appId))

  def group(gid: PathId): Option[Group] = {
    if (id == gid) Some(this) else {
      val restPath = gid.restOf(id)
      groups.find(_.id.restOf(id).root == restPath.root).flatMap(_.group(gid))
    }
  }

  def updateApp(path: PathId, fn: Option[AppDefinition] => AppDefinition, timestamp: Timestamp): Group = {
    val groupId = path.parent
    makeGroup(groupId).update(timestamp) { group =>
      if (group.id == groupId) group.putApplication(fn(group.apps.get(path))) else group
    }
  }

  def update(path: PathId, fn: Group => Group, timestamp: Timestamp): Group = {
    makeGroup(path).update(timestamp) { group =>
      if (group.id == path) fn(group) else group
    }
  }

  def updateApps(timestamp: Timestamp = Timestamp.now())(fn: AppDefinition => AppDefinition): Group = {
    update(timestamp) { group => group.copy(apps = group.apps.mapValues(fn)) }
  }

  def update(timestamp: Timestamp = Timestamp.now())(fn: Group => Group): Group = {
    def in(groups: List[Group]): List[Group] = groups match {
      case head :: rest => head.update(timestamp)(fn) :: in(rest)
      case Nil => Nil
    }
    fn(this.copy(groups = in(groups.toList).toSet, version = timestamp))
  }

  def updateGroup(fn: Group => Option[Group]): Option[Group] = {
    fn(this).map(_.copy(groups = groups.flatMap(_.updateGroup(fn))))
  }

  /** Removes the group with the given gid if it exists */
  def remove(gid: PathId, timestamp: Timestamp = Timestamp.now()): Group = {
    copy(groups = groups.filter(_.id != gid).map(_.remove(gid, timestamp)), version = timestamp)
  }

  /**
    * Add the given app definition to this group replacing any previously existing app definition with the same ID.
    *
    * If a group exists with a conflicting ID which does not contain any app definition, replace that as well.
    */
  private def putApplication(appDef: AppDefinition): Group = {
    copy(
      // If there is a group with a conflicting id which contains no app definitions,
      // replace it. Otherwise do not replace it. Validation will catch conflicting app/group IDs later.
      groups = groups.filter { group => group.id != appDef.id || group.containsApps },
      // replace potentially existing app definition
      apps = apps + (appDef.id -> appDef)
    )
  }

  /**
    * Remove the app with the given id if it is a direct child of this group.
    *
    * Use together with [[mesosphere.marathon.state.Group!.update(timestamp*]].
    */
  def removeApplication(appId: PathId): Group = copy(apps = apps - appId)

  def makeGroup(gid: PathId): Group = {
    if (gid.isEmpty) this //group already exists
    else {
      val (change, remaining) = groups.partition(_.id.restOf(id).root == gid.root)
      val toUpdate = change.headOption.getOrElse(Group.empty.copy(id = id.append(gid.rootPath)))
      this.copy(groups = remaining + toUpdate.makeGroup(gid.child))
    }
  }

  lazy val transitiveApps: Set[AppDefinition] = this.apps.values.toSet ++ groups.flatMap(_.transitiveApps)

  lazy val transitiveGroups: Set[Group] = groups.flatMap(_.transitiveGroups) + this

  lazy val transitiveAppGroups: Set[Group] = transitiveGroups.filter(_.apps.nonEmpty)

  lazy val applicationDependencies: List[(AppDefinition, AppDefinition)] = {
    var result = List.empty[(AppDefinition, AppDefinition)]
    val allGroups = transitiveGroups

    //group->group dependencies
    for {
      group <- allGroups
      dependencyId <- group.dependencies
      dependency <- allGroups.find(_.id == dependencyId)
      app <- group.transitiveApps
      dependentApp <- dependency.transitiveApps
    } result ::= app -> dependentApp

    //app->group/app dependencies
    for {
      group <- transitiveAppGroups
      app <- group.apps.values
      dependencyId <- app.dependencies
      dependentApp = transitiveApps.find(_.id == dependencyId).map(a => Set(a))
      dependentGroup = allGroups.find(_.id == dependencyId).map(_.transitiveApps)
      dependent <- dependentApp orElse dependentGroup getOrElse Set.empty
    } result ::= app -> dependent
    result
  }

  lazy val dependencyGraph: DirectedGraph[AppDefinition, DefaultEdge] = {
    require(id.isRoot)
    val graph = new DefaultDirectedGraph[AppDefinition, DefaultEdge](classOf[DefaultEdge])
    for (app <- transitiveApps)
      graph.addVertex(app)
    for ((app, dependent) <- applicationDependencies)
      graph.addEdge(app, dependent)
    new UnmodifiableDirectedGraph(graph)
  }

  def appsWithNoDependencies: Set[AppDefinition] = {
    val g = dependencyGraph
    g.vertexSet.filter { v => g.outDegreeOf(v) == 0 }.toSet
  }

  def hasNonCyclicDependencies: Boolean = {
    !new CycleDetector[AppDefinition, DefaultEdge](dependencyGraph).detectCycles()
  }

  /** @return true if and only if this group directly or indirectly contains app definitions. */
  def containsApps: Boolean = apps.nonEmpty || groups.exists(_.containsApps)

  def containsAppsOrGroups: Boolean = apps.nonEmpty || groups.nonEmpty

  def withNormalizedVersion: Group = copy(version = Timestamp(0))

  def withoutChildren: Group = copy(apps = Map.empty, groups = Set.empty)

  /**
    * Identify an other group as the same, if id and version is the same.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def equals(obj: Any): Boolean = {
    obj match {
      case that: Group => (that eq this) || (that.id == id && that.version == version)
      case _ => false
    }
  }

  /**
    * Compute the hashCode of an app only by id.
    * Override the default equals implementation generated by scalac, which is very expensive.
    */
  override def hashCode(): Int = id.hashCode()
}

object Group {
  def empty: Group = Group(PathId(Nil))
  def emptyWithId(id: PathId): Group = empty.copy(id = id)

  def fromProto(msg: GroupDefinition): Group = {
    Group(
      id = msg.getId.toPath,
      apps = msg.getAppsList.map(AppDefinition.fromProto).map { app => app.id -> app }(collection.breakOut),
      groups = msg.getGroupsList.map(fromProto).toSet,
      dependencies = msg.getDependenciesList.map(PathId.apply).toSet,
      version = Timestamp(msg.getVersion)
    )
  }

  def defaultApps: Map[AppDefinition.AppKey, AppDefinition] = Map.empty
  def defaultGroups: Set[Group] = Set.empty
  def defaultDependencies: Set[PathId] = Set.empty
  def defaultVersion: Timestamp = Timestamp.now()

  def validRootGroup(maxApps: Option[Int]): Validator[Group] = {
    case object doesNotExceedMaxApps extends Validator[Group] {
      override def apply(group: Group): Result = {
        maxApps.filter(group.transitiveApps.size > _).map { num =>
          Failure(Set(RuleViolation(
            group,
            s"""This Marathon instance may only handle up to $num Apps!
                |(Override with command line option --max_apps)""".stripMargin, None)))
        } getOrElse Success
      }
    }

    def validNestedGroup(base: PathId): Validator[Group] = validator[Group] { group =>
      group.id is validPathWithBase(base)
      group.apps.values as "apps" is every(AppDefinition.validNestedAppDefinition(group.id.canonicalPath(base)))
      group is noAppsAndGroupsWithSameName
      group is conditional[Group](_.id.isRoot)(noCyclicDependencies)
      group.groups is every(valid(validNestedGroup(group.id.canonicalPath(base))))
    }

    // We do not want a "/value" prefix, therefore we do not create nested validators with validator[Group]
    // but chain the validators directly.
    doesNotExceedMaxApps and
      validNestedGroup(PathId.empty) and
      ExternalVolumes.validRootGroup()
  }

  private def noAppsAndGroupsWithSameName: Validator[Group] =
    isTrue(s"Groups and Applications may not have the same identifier.") { group =>
      val groupIds = group.groups.map(_.id)
      val clashingIds = groupIds.intersect(group.apps.keySet)
      clashingIds.isEmpty
    }

  private def noCyclicDependencies: Validator[Group] =
    isTrue("Dependency graph has cyclic dependencies.") { _.hasNonCyclicDependencies }

}
