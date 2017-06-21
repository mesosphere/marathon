package mesosphere.marathon
package plugin

/**
  * A [[https://mesosphere.github.io/marathon/docs/application-groups.html Marathon Application Group]]
  */
trait Group {
  def id: PathId
  def apps: Iterable[(PathId, RunSpec)]
  def groupsById: Iterable[(PathId, Group)]
  def dependencies: Iterable[PathId]
}
