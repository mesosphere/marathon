package mesosphere.marathon.core.volume

import com.wix.accord.Validator
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Volume
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo }

trait LocalVolumes {
  /** @return a stream of task local volumes, extrapolating them from the app spec */
  def local(app: AppDefinition): Iterable[Task.LocalVolume]
  /** @return the aggregate mesos disk resources required for volumes */
  def diskSize(app: AppDefinition): Double
}

trait VolumeBuilderSupport {
  // TODO(jdef) these interfaces are not side-effect free; hard to think of a more
  // consistent way to impact the serialization process since we need to tweak different
  // types of things (volumes, envvar, container properties, ...)
  def containerInfo(v: Volume, ci: ContainerInfo.Builder): Option[ContainerInfo.Builder] = None
  def commandInfo(v: Volume, ct: ContainerInfo.Type, ci: CommandInfo.Builder): Option[CommandInfo.Builder] = None
}

/**
  * VolumeBuilderSupport routes builder calls to the appropriate volume provider.
  */
object VolumeBuilderSupport extends VolumeBuilderSupport {
  override def containerInfo(v: Volume, ci: ContainerInfo.Builder): Option[ContainerInfo.Builder] =
    VolumesModule.providerRegistry(v).filter(_.isInstanceOf[VolumeBuilderSupport]).
      map(_.asInstanceOf[VolumeBuilderSupport]).
      flatMap(_.containerInfo(v, ci))

  override def commandInfo(v: Volume, ct: ContainerInfo.Type, ci: CommandInfo.Builder): Option[CommandInfo.Builder] =
    VolumesModule.providerRegistry(v).filter(_.isInstanceOf[VolumeBuilderSupport]).
      map(_.asInstanceOf[VolumeBuilderSupport]).
      flatMap(_.commandInfo(v, ct, ci))
}

trait VolumeProviderRegistry {
  /** @return the VolumeProvider interface registered for the given volume */
  def apply[T <: Volume](v: T): Option[VolumeProvider[T]];

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
  lazy val builders: VolumeBuilderSupport = VolumeBuilderSupport
}
