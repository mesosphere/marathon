package mesosphere.marathon.core.volume

import com.wix.accord.Validator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, Container }
import mesosphere.marathon.state.{ PersistentVolume, Volume }
import mesosphere.marathon.core.volume.impl._

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
  /** validation implements this provider's specific validation rules */
  val validation: Validator[Volume]

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
}

/**
  * API facade for callers interested in storage volumes
  */
object VolumesModule {
  lazy val localVolumes: LocalVolumes = AgentVolumeProvider
  lazy val providers: VolumeProviderRegistry = StaticRegistry
  lazy val updates: ContextUpdate = ContextUpdate
}
