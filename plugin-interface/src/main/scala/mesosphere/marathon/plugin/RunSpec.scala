package mesosphere.marathon.plugin

/**
  * A Marathon RunSpec Definition
  */
trait RunSpec {
  def id: PathId
  def user: Option[String]
  def env: Map[String, EnvVarValue]
  def labels: Map[String, String]
  def acceptedResourceRoles: Option[Set[String]]
  /** secrets maps a secret ID to a secret specification; a secret ID is unique within the context of the [[RunSpec]] */
  def secrets: Map[String, Secret]
}
