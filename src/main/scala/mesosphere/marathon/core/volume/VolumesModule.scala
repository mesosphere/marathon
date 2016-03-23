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
  protected def containerInfo(ci: ContainerContext, v: Volume): Option[ContainerContext] = None
  protected def commandInfo(cm: CommandContext, v: Volume): Option[CommandContext] = None

  final def apply[C <: Context](c: C, v: Volume): Option[C] = {
    c match {
      case ctx: ContainerContext => containerInfo(ctx, v).asInstanceOf[Option[C]]
      case ctx: CommandContext   => commandInfo(ctx, v).asInstanceOf[Option[C]]
    }
  }

  final def apply[C <: Context](volumes: Iterable[Volume])(initialContext: () => C): Option[C] = {
    if (volumes.isEmpty) None
    else {
      var cc = initialContext()
      volumes.foreach { vol => cc = apply(cc, vol).getOrElse(cc) }
      Some(cc)
    }
  }
}

sealed trait Context
final case class ContainerContext(ci: ContainerInfo.Builder) extends Context
final case class CommandContext(ct: ContainerInfo.Type, ci: CommandInfo.Builder) extends Context

/**
  * VolumeBuilderSupport routes builder calls to the appropriate volume provider.
  */
object VolumeBuilderSupport extends VolumeBuilderSupport {
  override protected def containerInfo(ci: ContainerContext, v: Volume): Option[ContainerContext] =
    VolumesModule.providerRegistry(v).filter(_.isInstanceOf[VolumeBuilderSupport]).
      map(_.asInstanceOf[VolumeBuilderSupport]).
      flatMap(_.containerInfo(ci, v))

  override protected def commandInfo(cm: CommandContext, v: Volume): Option[CommandContext] =
    VolumesModule.providerRegistry(v).filter(_.isInstanceOf[VolumeBuilderSupport]).
      map(_.asInstanceOf[VolumeBuilderSupport]).
      flatMap(_.commandInfo(cm, v))
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

object VolumesModule {
  lazy val localVolumes: LocalVolumes = AgentVolumeProvider
  lazy val providerRegistry: VolumeProviderRegistry = VolumeProvider
  lazy val builders: VolumeBuilderSupport = VolumeBuilderSupport
}
