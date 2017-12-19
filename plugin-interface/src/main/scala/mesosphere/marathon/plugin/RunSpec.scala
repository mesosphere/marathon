package mesosphere.marathon
package plugin

/**
  * A Marathon RunSpec Definition
  */
trait RunSpec {

  /**
    * The unique id of this run specification
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

  /**
    * The networks that this run specification will join.
    */
  val networks: Seq[NetworkSpec]

  /**
    * Volume definitions
    */
  val volumes: Seq[VolumeSpec]

  /**
    * Volume mounts.
    */
  val volumeMounts: Seq[VolumeMountSpec]
}

/**
  * An application is a run spec that launches a single task.
  */
trait ApplicationSpec extends RunSpec {

  /**
    * The user to execute the app task
    */
  val user: Option[String]

  /**
    * The environment of this app.
    */
  val env: Map[String, EnvVarValue]

  /**
    * The labels in that app.
    */
  val labels: Map[String, String]
}

/**
  * A Marathon Container definition
  */
trait ContainerSpec {

  /**
    * The name of the container spec.
    */
  val name: String

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

  /**
    * Volume mounts.
    */
  val volumeMounts: Seq[VolumeMountSpec]
}

/**
  * A network definition.
  */
trait NetworkSpec {

  /**
    * Optional labels for a given network, may be empty.
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

  /**
    * The environment shared for all containers inside this pod.
    */
  val env: Map[String, EnvVarValue]

  /**
    * The labels in that pod.
    */
  val labels: Map[String, String]
}

/**
  * VolumeSpec is a base trait for all the volume types supported by
  * Marathon. A volume can have an optional name. An application volume
  * does not have a name, whereas a pod volume does have one. This is
  * due to the difference in the app and pod JSON definitions. In case
  * of apps, both volume and its mount point are defined using the same
  * JSON object. On the other hand, in case of pods, a volume is defined
  * separately from its mount points, which refer to the volume using its
  * name.
  */
trait VolumeSpec {
  /**
    * A volume name.
    */
  val name: Option[String]
}

/**
  * A volume referring to a secret to be made available to containers.
  */
trait SecretVolumeSpec extends VolumeSpec {
  /**
    * A secret name.
    */
  val secret: String
}

/**
  * A volume mount specifying a path to mount a volume at.
  */
trait VolumeMountSpec {
  /**
    * The volume name this mount is for.
    */
  val volumeName: Option[String]

  /**
    * A mount path for the corresponding volume.
    */
  val mountPath: String

  /**
    * This field specifies whether to mount the volume
    * as read-only or not.
    */
  val readOnly: Boolean
}
