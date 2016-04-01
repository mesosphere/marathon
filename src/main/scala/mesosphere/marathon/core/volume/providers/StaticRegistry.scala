package mesosphere.marathon.core.volume.providers

import mesosphere.marathon.core.volume._
import mesosphere.marathon.state.{ DockerVolume, PersistentVolume, Volume }

/**
  * StaticRegistry is a fixed, precomputed storage provider registry
  */
protected[volume] object StaticRegistry extends PersistentVolumeProviderRegistry {
  def make(prov: PersistentVolumeProvider[PersistentVolume]*): Map[String, PersistentVolumeProvider[PersistentVolume]] =
    prov.map(p => p.name -> p).toMap

  val registry = make(
    // list supported providers here; all MUST provide a non-empty "name" trait
    ResidentVolumeProvider,
    DVDIProvider
  )

  def providerForName(name: Option[String]): Option[PersistentVolumeProvider[PersistentVolume]] =
    registry.get(name.getOrElse(ResidentVolumeProvider.name))

  def apply[T <: Volume](v: T): Option[VolumeProvider[T]] =
    v match {
      case dv: DockerVolume     => Some(DockerHostVolumeProvider.asInstanceOf[VolumeProvider[T]])
      case pv: PersistentVolume => providerForName(pv.persistent.providerName).map(_.asInstanceOf[VolumeProvider[T]])
    }

  def apply(name: Option[String]): Option[PersistentVolumeProvider[PersistentVolume]] = providerForName(name)

  val commandInjector = new CommandInjector[PersistentVolume] {
    override def inject(c: CommandContext, pv: PersistentVolume): CommandContext = {
      registry.get(pv.persistent.providerName.getOrElse(ResidentVolumeProvider.name)).
        fold(c)(p => p.commandInjector.inject(c, pv))
    }
  }

  val containerInjector = new ContainerInjector[Volume] {
    override def inject(c: ContainerContext, v: Volume): ContainerContext = {
      v match {
        case dv: DockerVolume => DockerHostVolumeProvider.containerInjector.inject(c, dv)
        case pv: PersistentVolume =>
          registry.get(pv.persistent.providerName.getOrElse(ResidentVolumeProvider.name)).
            fold(c)(p => p.containerInjector.inject(c, pv))
      }
    }
  }
}
