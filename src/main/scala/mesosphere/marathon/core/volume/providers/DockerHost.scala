package mesosphere.marathon.core.volume.providers

import com.wix.accord.Validator
import com.wix.accord.combinators.NilValidator
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ Volume => MesosVolume }

/**
  * DockerHostVolumeProvider handles Docker volumes that a user would like to mount at
  * predetermined host and container paths. Docker host volumes are not intended to be used
  * with "non-local" docker volume drivers. If you want to use a docker volume driver then
  * use a PersistentVolume instead.
  */
protected[volume] case object DockerHostVolumeProvider
    extends DecoratorHelper[DockerVolume]
    with VolumeProvider[DockerVolume] {
  val name = "docker" // only because we should have a non-empty name

  /** no special case validation here, it's handled elsewhere */
  val validation: Validator[Volume] = new NilValidator[Volume]

  // no provider-specific rules at the container level
  val containerValidation: Validator[Container] = new NilValidator[Container]

  // no provider-specific rules at the group level
  val groupValidation: Validator[Group] = new NilValidator[Group]

  /** DockerVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: DockerVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build

  protected def accepts(dv: DockerVolume): Boolean = true

  override def decoratedContainer(ctx: ContainerContext, dv: DockerVolume): ContainerContext = {
    // TODO(jdef) check that this is a DOCKER container type?
    ContainerContext(ctx.ci.addVolumes(toMesosVolume(dv)))
  }

  override def apply(container: Option[Container]): Iterable[DockerVolume] =
    container.fold(Seq.empty[DockerVolume])(_.volumes.collect{ case vol: DockerVolume => vol })
}
