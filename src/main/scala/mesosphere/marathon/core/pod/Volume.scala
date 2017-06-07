package mesosphere.marathon
package core.pod

import mesosphere.marathon.plugin.{ PodSecretVolumeSpec, PodVolumeSpec, VolumeMountSpec }

sealed trait Volume extends Product with Serializable with PodVolumeSpec {
  val name: String
}

/**
  * Ephemeral volumes share the lifetime of the pod instance they're used within and serve to
  * provide temporary "scratch" space that sub-containers may use to share files, sockets, etc.
  */
case class EphemeralVolume(name: String) extends Volume

/**
  * A host volume refers to some file or directory on the host that should be made available
  * to one or more sub-containers.
  */
case class HostVolume(name: String, hostPath: String) extends Volume

/**
  * A volume referring to an existing secret.
  */
case class SecretVolume(name: String, secret: String) extends Volume with PodSecretVolumeSpec

case class VolumeMount(name: String, mountPath: String, readOnly: Option[Boolean] = None) extends VolumeMountSpec
