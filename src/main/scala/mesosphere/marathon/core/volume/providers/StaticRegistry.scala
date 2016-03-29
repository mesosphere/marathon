package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import com.wix.accord.combinators.Fail
import com.wix.accord.Validator
import com.wix.accord.ViolationBuilder._
import mesosphere.marathon.core.volume.{ VolumeProvider, VolumeProviderRegistry }
import mesosphere.marathon.state.{ DockerVolume, PersistentVolume, Volume }

/**
  * StaticRegistry is a fixed, precomputed storage provider registry
  */
protected[volume] object StaticRegistry extends VolumeProviderRegistry {

  def make(prov: VolumeProvider[Volume]*): Map[String, VolumeProvider[Volume]] = {
    prov.foldLeft(Map.empty[String, VolumeProvider[Volume]]) { (m, p) => m + (p.name -> p) }
  }

  val registry = make(
    // list supported providers here
    AgentVolumeProvider,
    DockerHostVolumeProvider,
    DVDIProvider
  )

  def providerForName(name: Option[String]): Option[VolumeProvider[Volume]] =
    registry.get(name.getOrElse(AgentVolumeProvider.name))

  override def apply[T <: Volume](v: T): Option[VolumeProvider[T]] =
    v match {
      case dv: DockerVolume     => Some(DockerHostVolumeProvider.asInstanceOf[VolumeProvider[T]])
      case pv: PersistentVolume => providerForName(pv.persistent.providerName).map(_.asInstanceOf[VolumeProvider[T]])
    }

  override def apply(name: Option[String]): Option[VolumeProvider[Volume]] = providerForName(name)

  override def known(): Validator[Option[String]] =
    new NullSafeValidator[Option[String]](
      test = { !apply(_).isEmpty },
      failure = _ -> s"is not one of (${registry.keys.mkString(",")})"
    )

  override def approved[T <: Volume](name: Option[String]): Validator[T] =
    apply(name).fold(new Fail[T]("is an illegal volume specification").asInstanceOf[Validator[T]])(_.validation)
}
