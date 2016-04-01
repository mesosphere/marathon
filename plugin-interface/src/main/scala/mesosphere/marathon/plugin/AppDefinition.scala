package mesosphere.marathon.plugin

/**
  * A Marathon Application Definition
  */
trait AppDefinition {
  def id: PathId
  def user: Option[String]
  def env: Map[String, String]
  def labels: Map[String, String]
  def acceptedResourceRoles: Option[Set[String]]
}
