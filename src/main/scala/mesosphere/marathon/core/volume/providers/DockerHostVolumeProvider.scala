package mesosphere.marathon.core.volume.providers

import com.wix.accord.Validator
import com.wix.accord.combinators.NilValidator
import com.wix.accord.dsl._
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
    extends VolumeProvider[DockerVolume] {
  val appValidation: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app.container.get.`type` is equalTo("DOCKER")
  }

  // no provider-specific rules at the group level
  val groupValidation: Validator[Group] = new NilValidator[Group]

  /** DockerVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: DockerVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build

  val containerInjector = new ContainerInjector[DockerVolume] {
    override def inject(ctx: ContainerContext, dv: DockerVolume): ContainerContext =
      ContainerContext(ctx.container.addVolumes(toMesosVolume(dv)))
  }

  override def collect(container: Container): Iterable[DockerVolume] =
    container.volumes.collect{ case vol: DockerVolume => vol }
}
