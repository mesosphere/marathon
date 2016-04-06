package mesosphere.marathon.core.externalvolume

import com.wix.accord._
import mesosphere.marathon.core.externalvolume.providers._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ ContainerInfo, CommandInfo }

/**
  * ExternalVolumeProvider is an interface implemented by external storage volume providers
  */
trait ExternalVolumeProvider {
  val name: String

  /** validations on different levels */
  val appValidation: Validator[AppDefinition]
  val volumeValidation: Validator[ExternalVolume]
  val groupValidation: Validator[Group]

  /** build adds v to the given builder **/
  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit

  /** build adds ev to the given builder **/
  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, ev: ExternalVolume): Unit
}

trait ExternalVolumeProviderRegistry {
  /**
    * @return the ExternalVolumeProvider interface registered for the given name
    */
  def get(name: String): Option[ExternalVolumeProvider]

  def all: Iterable[ExternalVolumeProvider]
}

/**
  * API facade for callers interested in storage volumes
  */
object VolumesModule {
  lazy val providers: ExternalVolumeProviderRegistry = StaticExternalVolumeProviderRegistry

  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit = {
    providers.get(v.external.providerName).foreach { _.build(builder, v) }
  }

  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, v: ExternalVolume): Unit = {
    providers.get(v.external.providerName).foreach { _.build(containerType, builder, v) }
  }

  def validExternalVolume: Validator[ExternalVolume] = new Validator[ExternalVolume] {
    def apply(ev: ExternalVolume) = providers.get(ev.external.providerName) match {
      case Some(p) => p.volumeValidation(ev)
      case None    => Failure(Set(RuleViolation(None, "is unknown provider", Some("external/providerName"))))
    }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = providers.all.map(_.appValidation).map(validate(app)(_)).fold(Success)(_ and _)
  }

  /** @return a validator that checks the validity of a group given the related volume providers */
  def validGroup(): Validator[Group] = new Validator[Group] {
    def apply(grp: Group) = providers.all.map(_.groupValidation).map(validate(grp)(_)).fold(Success)(_ and _)
  }
}
