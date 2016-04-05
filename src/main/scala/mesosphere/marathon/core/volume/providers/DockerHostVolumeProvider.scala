package mesosphere.marathon.core.volume.providers

import com.wix.accord.Validator
import com.wix.accord.combinators.NilValidator
import com.wix.accord.dsl._
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ ContainerInfo, Volume => MesosVolume }

/**
  * DockerHostVolumeProvider handles Docker volumes that a user would like to mount at
  * predetermined host and container paths. Docker host volumes are not intended to be used
  * with "non-local" docker volume drivers. If you want to use a docker volume driver then
  * use a PersistentVolume instead.
  */
protected[volume] case object DockerHostVolumeProvider
    extends VolumeProvider[DockerVolume] {
  val appValidation: Validator[AppDefinition] = validator[AppDefinition] { app =>
    app.container.get.`type` as "app.container.type" is equalTo(ContainerInfo.Type.DOCKER)
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

  def build(builder: ContainerInfo.Builder, v: Volume): Unit = v match {
    case dv: DockerVolume if builder.getType == ContainerInfo.Type.DOCKER =>
      builder.addVolumes(toMesosVolume(dv))
  }

  override def collect(container: Container): Iterable[DockerVolume] =
    container.volumes.collect{ case vol: DockerVolume => vol }
}
