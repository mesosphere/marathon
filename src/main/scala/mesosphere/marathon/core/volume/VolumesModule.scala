package mesosphere.marathon.core.volume

import com.wix.accord.Validator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Volume

trait LocalVolumes {
  /** @return a stream of task local volumes, extrapolating them from the app spec */
  def local(app: AppDefinition): Iterable[Task.LocalVolume]
  /** @return the aggregate mesos disk resources required for volumes */
  def diskSize(app: AppDefinition): Double
}

trait VolumeProviderRegistry {
  /**
    * @return the VolumeProvider interface registered for the given name; if name is None then
    * the default VolumeProvider implementation is returned. None is returned if Some name is given
    * but no volume provider is registered for that name.
    */
  def apply(name: Option[String]): Option[VolumeProvider[_ <: Volume]];

  /** @return a validator that checks the validity of a volume provider name */
  def known(): Validator[Option[String]];

  /** @return a validator that checks the validity of a volume given the volume provider name */
  def approved[T <: Volume](name: Option[String]): Validator[T];
}

object VolumesModule {
  lazy val localVolumes: LocalVolumes = AgentVolumeProvider
  lazy val providerRegistry: VolumeProviderRegistry = VolumeProvider
}
