package mesosphere.marathon.state

import com.fasterxml.jackson.annotation.JsonIgnore
import mesosphere.marathon.Protos.GroupDefinition
import mesosphere.marathon.state.Group._
import mesosphere.marathon.state.PathId._
import org.jgrapht.DirectedGraph
import org.jgrapht.alg.CycleDetector
import org.jgrapht.graph._

import scala.collection.JavaConversions._

case class Group(
    id: PathId,
    apps: Set[AppDefinition] = defaultApps,
    groups: Set[Group] = defaultGroups,
    dependencies: Set[PathId] = defaultDependencies,
    version: Timestamp = defaultVersion) extends MarathonState[GroupDefinition, Group] {

  override def mergeFromProto(msg: GroupDefinition): Group = Group.fromProto(msg)
  override def mergeFromProto(bytes: Array[Byte]): Group = Group.fromProto(GroupDefinition.parseFrom(bytes))

  override def toProto: GroupDefinition = {
    GroupDefinition.newBuilder
      .setId(id.toString)
      .setVersion(version.toString)
      .addAllApps(apps.map(_.toProto))
      .addAllGroups(groups.map(_.toProto))
      .addAllDependencies(dependencies.map(_.toString))
      .build()
  }

  def findGroup(fn: Group => Boolean): Option[Group] = {
    def in(groups: List[Group]): Option[Group] = groups match {
      case head :: rest => if (fn(head)) Some(head) else in(rest).orElse(in(head.groups.toList))
      case Nil          => None
    }
    if (fn(this)) Some(this) else in(groups.toList)
  }

  def app(appId: PathId): Option[AppDefinition] = group(appId.parent).flatMap(_.apps.find(_.id == appId))

  def group(gid: PathId): Option[Group] = {
    if (id == gid) Some(this) else {
      val restPath = gid.restOf(id)
      groups.find(_.id.restOf(id).root == restPath.root).flatMap(_.group(gid))
    }
  }

  def updateApp(path: PathId, fn: Option[AppDefinition] => AppDefinition, timestamp: Timestamp): Group = {
    val groupId = path.parent
    makeGroup(groupId).update(timestamp) { group =>
      if (group.id == groupId) group.putApplication(fn(group.apps.find(_.id == path))) else group
    }
  }

  def update(path: PathId, fn: Group => Group, timestamp: Timestamp): Group = {
    makeGroup(path).update(timestamp) { group =>
      if (group.id == path) fn(group) else group
    }
  }

  def updateApp(timestamp: Timestamp = Timestamp.now())(fn: AppDefinition => AppDefinition): Group = {
    update(timestamp) { group => group.copy(apps = group.apps.map(fn)) }
  }

  def update(timestamp: Timestamp = Timestamp.now())(fn: Group => Group): Group = {
    def in(groups: List[Group]): List[Group] = groups match {
      case head :: rest => head.update(timestamp)(fn) :: in(rest)
      case Nil          => Nil
    }
    fn(this.copy(groups = in(groups.toList).toSet, version = timestamp))
  }

  /** Removes the group with the given gid if it exists */
  def remove(gid: PathId, timestamp: Timestamp = Timestamp.now()): Group = {
    copy(groups = groups.filter(_.id != gid).map(_.remove(gid, timestamp)), version = timestamp)
  }

  /**
    * Add the given app definition to this group replacing any priorly existing app definition with the same ID.
    *
    * If a group exists with a conflicting ID which does not contain any app definition, replace that as well.
    */
  private def putApplication(appDef: AppDefinition): Group = {
    copy(
      // If there is a group with a conflicting id which contains no app definitions,
      // replace it. Otherwise do not replace it. Validation will catch conflicting app/group IDs later.
      groups = groups.filter { group => group.id != appDef.id || group.containsApps },
      // replace potentially existing app definition
      apps = apps.filter(_.id != appDef.id) + appDef
    )
  }

  /**
    * Remove the app with the given id if it is a direct child of this group.
    *
    * Use together with [[mesosphere.marathon.state.Group!.update(timestamp*]].
    */
  def removeApplication(appId: PathId): Group = copy(apps = apps.filter(_.id != appId))

  def makeGroup(gid: PathId): Group = {
    val restPath = gid.restOf(id)
    if (gid.isEmpty || restPath.isEmpty) this //group already exists
    else {
      val (change, remaining) = groups.partition(_.id.restOf(id).root == restPath.root)
      val toUpdate = change.headOption.getOrElse(Group.empty.copy(id = id.append(restPath.rootPath)))
      val nestedUpdate = if (restPath.isEmpty) toUpdate else toUpdate.makeGroup(restPath.child)
      this.copy(groups = remaining + nestedUpdate)
    }
  }

  def transitiveApps: Set[AppDefinition] = this.apps ++ groups.flatMap(_.transitiveApps)

  def transitiveGroups: Set[Group] = groups.flatMap(_.transitiveGroups) + this

  def transitiveAppGroups: Set[Group] = transitiveGroups.filter(_.apps.nonEmpty)

  @JsonIgnore
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
      app <- group.apps
      dependencyId <- app.dependencies
      dependentApp = transitiveApps.find(_.id == dependencyId).map(a => Set(a))
      dependentGroup = allGroups.find(_.id == dependencyId).map(_.transitiveApps)
      dependent <- dependentApp orElse dependentGroup getOrElse Set.empty
    } result ::= app -> dependent
    result
  }

  def applicationDependenciesML: String = {
    val res = for ((app, dependent) <- applicationDependencies) yield {
      s"[${app.id}]->[${dependent.id}]"
    }
    res.mkString("http://yuml.me/diagram/plain/class/", ", ", "")
  }

  def dependencyGraph: DirectedGraph[AppDefinition, DefaultEdge] = {
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
  @JsonIgnore
  def containsApps: Boolean = apps.nonEmpty || groups.exists(_.containsApps)

  @JsonIgnore
  def containsAppsOrGroups: Boolean = apps.nonEmpty || groups.nonEmpty

  @JsonIgnore
  def withNormalizedVersion: Group = copy(version = Timestamp(0))

  @JsonIgnore
  def withoutChildren: Group = copy(apps = Set.empty, groups = Set.empty)
}

object Group {
  def empty: Group = Group(PathId(Nil))
  def emptyWithId(id: PathId): Group = empty.copy(id = id)

  def fromProto(msg: GroupDefinition): Group = {
    Group(
      id = msg.getId.toPath,
      apps = msg.getAppsList.map(AppDefinition.fromProto).toSet,
      groups = msg.getGroupsList.map(fromProto).toSet,
      dependencies = msg.getDependenciesList.map(PathId.apply).toSet,
      version = Timestamp(msg.getVersion)
    )
  }

  def defaultApps: Set[AppDefinition] = Set.empty
  def defaultGroups: Set[Group] = Set.empty
  def defaultDependencies: Set[PathId] = Set.empty
  def defaultVersion: Timestamp = Timestamp.now()
}

