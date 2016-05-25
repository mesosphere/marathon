package mesosphere.marathon.plugin

/**
  * A Marathon Application Definition
  */
trait AppDefinition {
  def id: PathId
  def user: Option[String]
  def env: Map[String, EnvVarValue]
  def labels: Map[String, String]
  def acceptedResourceRoles: Option[Set[String]]
  /** secrets maps a secret ID to a secret specification; a secret ID is unique within the context of the [[AppDefinition]] */
  def secrets: Map[String, Secret]
}
