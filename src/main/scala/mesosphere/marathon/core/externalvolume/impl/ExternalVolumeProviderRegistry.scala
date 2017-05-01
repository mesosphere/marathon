package mesosphere.marathon
package core.externalvolume.impl

private[externalvolume] trait ExternalVolumeProviderRegistry {
  /**
    * @return the ExternalVolumeProvider interface registered for the given name
    */
  def get(name: String): Option[ExternalVolumeProvider]

  def all: Seq[ExternalVolumeProvider]
}
