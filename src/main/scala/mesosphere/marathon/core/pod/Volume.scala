package mesosphere.marathon.core.pod

sealed trait Volume extends Product with Serializable {
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
