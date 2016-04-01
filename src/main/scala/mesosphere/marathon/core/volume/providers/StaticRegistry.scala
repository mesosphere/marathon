package mesosphere.marathon.core.volume.providers

import com.wix.accord.{Validator, _}
import com.wix.accord.combinators.Fail
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state.{DockerVolume, PersistentVolume, Volume}

/**
  * StaticRegistry is a fixed, precomputed storage provider registry
  */
protected[volume] object StaticRegistry extends PersistentVolumeProviderRegistry {
  def make(prov: PersistentVolumeProvider[PersistentVolume]*):
    Map[String, PersistentVolumeProvider[PersistentVolume]] = prov.map(p => p.name -> p).toMap

  val registry = make(
    // list supported providers here; all MUST provide a non-empty "name" trait
    AgentVolumeProvider,
    DVDIProvider
  )

  def providerForName(name: Option[String]): Option[PersistentVolumeProvider[PersistentVolume]] =
    registry.get(name.getOrElse(AgentVolumeProvider.name))

  override def apply[T <: Volume](v: T): Option[VolumeProvider[T]] =
    v match {
      case dv: DockerVolume     => Some(DockerHostVolumeProvider.asInstanceOf[PersistentVolumeProvider[T]])
      case pv: PersistentVolume => providerForName(pv.persistent.providerName).map(_.asInstanceOf[VolumeProvider[T]])
    }

  override def apply(name: Option[String]): Option[PersistentVolumeProvider[Volume]] = providerForName(name)

  override def known(): Validator[Option[String]] =
    new NullSafeValidator[Option[String]](
      test = { apply(_).isDefined },
      failure = _ -> s"is not one of (${registry.keys.mkString(",")})"
    )

  override def approved[T <: Volume](name: Option[String]): Validator[T] =
    apply(name).fold(new Fail[T]("is an illegal volume specification").asInstanceOf[Validator[T]])(_.validation)
}
