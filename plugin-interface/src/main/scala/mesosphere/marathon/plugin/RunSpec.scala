package mesosphere.marathon.plugin

/**
  * A Marathon Container definition
  */
trait ContainerSpec {
  /**
    * The user to execute the container task
    */
  def user: Option[String]

  /**
    * The environment of this container.
    */
  def env: Map[String, EnvVarValue]

  /**
    * The labels in that container
    */
  def labels: Map[String, String]
}

/**
  * A Marathon RunSpec Definition
  */
trait RunSpec {

  /**
    * The uniqie id of this run specification
    */
  def id: PathId

  /**
    * The Mesos resource roles that are accepted
    */
  def acceptedResourceRoles: Set[String]

  /**
    * All defined secret definitions
    */
  def secrets: Map[String, Secret]

  /**
    * All containers of this run specification
    */
  def containers: Seq[ContainerSpec]
}
