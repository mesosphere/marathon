package mesosphere.marathon.plugin

/**
  * A Marathon RunSpec Definition
  */
trait RunSpec {

  /**
    * The uniqie id of this run specification
    */
  val id: PathId

  /**
    * The Mesos resource roles that are accepted
    */
  val acceptedResourceRoles: Set[String]

  /**
    * All defined secret definitions
    */
  val secrets: Map[String, Secret]

}

/**
  * An application is a run spec that launches a single task.
  */
trait ApplicationSpec extends RunSpec {

  /**
    * The user to execute the container task
    */
  val user: Option[String]

  /**
    * The environment of this container.
    */
  val env: Map[String, EnvVarValue]

  /**
    * The labels in that container
    */
  val labels: Map[String, String]
}

/**
  * A Marathon Container definition
  */
trait ContainerSpec {
  /**
    * The user to execute the container task
    */
  val user: Option[String]

  /**
    * The environment of this container.
    */
  val env: Map[String, EnvVarValue]

  /**
    * The labels in that container
    */
  val labels: Map[String, String]
}

/**
  * A pod is a run spec that launches a task group.
  */
trait PodSpec extends RunSpec {

  /**
    * All containers of this run specification
    */
  val containers: Seq[ContainerSpec]

}
