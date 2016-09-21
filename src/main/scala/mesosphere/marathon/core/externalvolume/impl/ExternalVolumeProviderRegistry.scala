package mesosphere.marathon.core.externalvolume.impl

import scala.collection.immutable.Seq

private[externalvolume] trait ExternalVolumeProviderRegistry {
  /**
    * @return the ExternalVolumeProvider interface registered for the given name
    */
  def get(name: String): Option[ExternalVolumeProvider]

  def all: Seq[ExternalVolumeProvider]
}
