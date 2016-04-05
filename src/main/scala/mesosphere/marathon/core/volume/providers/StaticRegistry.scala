package mesosphere.marathon.core.volume.providers

import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._

/**
  * StaticRegistry is a fixed, precomputed storage provider registry
  */
protected[volume] object StaticRegistry extends ExternalVolumeProviderRegistry {
  def make(prov: ExternalVolumeProvider*): Map[String, ExternalVolumeProvider] =
    prov.map(p => p.name -> p).toMap

  val registry = make(
    // list supported providers here; all MUST provide a non-empty "name" trait
    DVDIProvider
  )

  def providerForName(name: String): Option[ExternalVolumeProvider] = registry.get(name)

  def apply[T <: Volume](v: T): Option[VolumeProvider[T]] =
    v match {
      case dv: DockerVolume    => Some(DockerHostVolumeProvider.asInstanceOf[VolumeProvider[T]])
      case pv: ExternalVolume  => providerForName(pv.external.providerName).map(_.asInstanceOf[VolumeProvider[T]])
      case _: PersistentVolume => None
    }

  def apply(name: String): Option[ExternalVolumeProvider] = providerForName(name)
}
