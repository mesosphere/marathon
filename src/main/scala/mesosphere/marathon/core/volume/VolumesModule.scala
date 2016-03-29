package mesosphere.marathon.core.volume

import com.wix.accord._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import mesosphere.marathon.core.volume.providers._

trait LocalVolumes {
  self: VolumeProvider[PersistentVolume] =>

  /** @return a stream of task local volumes, extrapolating them from the app spec */
  def local(app: AppDefinition): Iterable[Task.LocalVolume] = {
    self.apply(app.container).map{ volume => Task.LocalVolume(Task.LocalVolumeId(app.id, volume), volume) }
  }
  /** @return the aggregate mesos disk resources required for volumes */
  def diskSize(container: Option[Container]): Double = {
    self.apply(container).map(_.persistent.size).flatten.sum.toDouble
  }
}

/**
  * VolumeProvider is an interface implemented by storage volume providers
  */
trait VolumeProvider[+T <: Volume] {
  /** name uniquely identifies this volume provider */
  val name: String
  /** validation implements a provider's volume validation rules */
  val validation: Validator[Volume]
  /** containerValidation implements a provider's container validation rules */
  val containerValidation: Validator[Container]
  /** groupValidation implements a provider's group validation rules */
  val groupValidation: Validator[Group]

  /** apply scrapes volumes from an application definition that are supported this volume provider */
  def apply(container: Option[Container]): Iterable[T]
}

trait VolumeProviderRegistry {
  /** @return the VolumeProvider interface registered for the given volume */
  def apply[T <: Volume](v: T): Option[VolumeProvider[T]];

  /**
    * @return the VolumeProvider interface registered for the given name; if name is None then
    * the default VolumeProvider implementation is returned. None is returned if Some name is given
    * but no volume provider is registered for that name.
    */
  def apply(name: Option[String]): Option[VolumeProvider[Volume]];

  /** @return a validator that checks the validity of a volume provider name */
  def known(): Validator[Option[String]];

  /** @return a validator that checks the validity of a volume given the volume provider name */
  def approved[T <: Volume](name: Option[String]): Validator[T];

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validContainer(): Validator[Container] = new Validator[Container] {
    def apply(ct: Container) = ct match {
      // scalastyle:off null
      case null => Failure(Set(RuleViolation(null, "is a null", None)))
      // scalastyle:on null

      // grab all related volume providers and apply their containerValidation
      case _ => ct.volumes.map(VolumesModule.providers(_)).flatten.distinct.
        map(_.containerValidation).map(validate(ct)(_)).fold(Success)(_ and _)
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
        ct.volumes.map(VolumesModule.providers(_))
      }.flatten.map{ p => validate(grp)(p.groupValidation) }.fold(Success)(_ and _)
    }
  }
}

/**
  * API facade for callers interested in storage volumes
  */
object VolumesModule {
  lazy val localVolumes: LocalVolumes = AgentVolumeProvider
  lazy val providers: VolumeProviderRegistry = StaticRegistry
  lazy val updates: ContextUpdate = ContextUpdate
}
