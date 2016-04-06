package mesosphere.marathon.core.externalvolume

import com.wix.accord._
import mesosphere.marathon.core.externalvolume.providers._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ ContainerInfo, CommandInfo }

/**
  * VolumeProvider is an interface implemented by storage volume providers
  */
trait VolumeProvider[+T <: Volume] {
  /** appValidation implements a provider's app validation rules */
  val appValidation: Validator[AppDefinition]
  /** groupValidation implements a provider's group validation rules */
  val groupValidation: Validator[Group]

  /** collect scrapes volumes from an application definition that are supported by this volume provider */
  def collect(container: Container): Iterable[T]

  /** build adds v to the given builder **/
  def build(builder: ContainerInfo.Builder, v: Volume): Unit
}

trait ExternalVolumeProvider extends VolumeProvider[ExternalVolume] {
  val name: String

  /**
    * see implicit validator in the ExternalVolume class for reference.
    */
  val volumeValidation: Validator[ExternalVolume]

  /** build adds ev to the given builder **/
  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, ev: ExternalVolume): Unit
}

trait ExternalVolumeProviderRegistry {
  /**
    * @return the ExternalVolumeProvider interface registered for the given name; if name is None then
    * the default PersistenVolumeProvider implementation is returned. None is returned if Some name is given
    * but no volume provider is registered for that name.
    */
  def apply(name: String): Option[ExternalVolumeProvider]
}

/**
  * API facade for callers interested in storage volumes
  */
object VolumesModule {
  lazy val providers: ExternalVolumeProviderRegistry = StaticExternalVolumeProviderRegistry

  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit = {
    providers(v.external.providerName).foreach { _.build(builder, v) }
  }

  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, v: ExternalVolume): Unit = {
    providers(v.external.providerName).foreach { _.build(containerType, builder, v) }
  }

  def validExternalVolume: Validator[ExternalVolume] = new Validator[ExternalVolume] {
    def apply(ev: ExternalVolume) = providers(ev.external.providerName) match {
      case Some(p) => p.volumeValidation(ev)
      case None    => Failure(Set(RuleViolation(None, "is unknown provider", Some("external/providerName"))))
    }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = app match {
      // scalastyle:off null
      case null => Failure(Set(RuleViolation(null, "is a null", None)))
      // scalastyle:on null

      // grab all related volume providers and apply their appValidation
      case _ => app.container.toSet[Container].flatMap{ ct =>
        ct.volumes.map({
          case ev: ExternalVolume => providers(ev.external.providerName)
          case _                  => None
        })
      }.flatten.map(_.appValidation).map(validate(app)(_)).fold(Success)(_ and _)
    }
  }

  /** @return a validator that checks the validity of a group given the related volume providers */
  def validGroup(): Validator[Group] = new Validator[Group] {
    def apply(grp: Group) = grp match {
      // scalastyle:off null
      case null => Failure(Set(RuleViolation(null, "is a null", None)))
      // scalastyle:on null

      // grab all related volume providers and apply their groupValidation
      case _ => grp.transitiveApps.flatMap{ app => app.container }.flatMap{ ct =>
        ct.volumes.map({
          case ev: ExternalVolume => providers(ev.external.providerName)
          case _                  => None
        })
      }.flatten.map{ p => validate(grp)(p.groupValidation) }.fold(Success)(_ and _)
    }
  }
}
