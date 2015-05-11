package mesosphere.marathon.api.v2.json

import mesosphere.marathon.state.{ Group, PathId, Timestamp }

case class V2Group(
    id: PathId,
    apps: Set[V2AppDefinition] = V2Group.defaultApps,
    groups: Set[V2Group] = V2Group.defaultGroups,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion) {

  /**
    * Returns the canonical internal representation of this API-specific
    * group.
    */
  def toGroup(): Group =
    Group(
      id,
      apps.map(_.toAppDefinition),
      groups.map(_.toGroup),
      dependencies,
      version
    )

}

object V2Group {

  val defaultApps: Set[V2AppDefinition] = Set.empty
  val defaultGroups: Set[V2Group] = Set.empty

  def apply(group: Group): V2Group =
    V2Group(
      group.id,
      group.apps.map(V2AppDefinition(_)),
      group.groups.map(V2Group(_)),
      group.dependencies,
      group.version)
}
