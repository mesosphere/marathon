package mesosphere.marathon.core.externalvolume.impl

/**
  * Created by peter on 07/04/16.
  */
private[externalvolume] trait ExternalVolumeProviderRegistry {
  /**
    * @return the ExternalVolumeProvider interface registered for the given name
    */
  def get(name: String): Option[ExternalVolumeProvider]

  def all: Iterable[ExternalVolumeProvider]
}
