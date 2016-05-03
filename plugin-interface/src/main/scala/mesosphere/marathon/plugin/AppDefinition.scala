package mesosphere.marathon.plugin

trait EnvVarValue

/**
  * A Marathon Application Definition
  */
trait AppDefinition {
  def id: PathId
  def user: Option[String]
  def env: Map[String, EnvVarValue]
  def labels: Map[String, String]
  def acceptedResourceRoles: Option[Set[String]]
}
