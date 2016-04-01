package mesosphere.marathon.plugin

/**
  * A [[https://mesosphere.github.io/marathon/docs/application-groups.html Marathon Application Group]]
  */
trait Group {
  def id: PathId
  def apps: Iterable[AppDefinition]
  def groups: Iterable[Group]
  def dependencies: Iterable[PathId]
}
