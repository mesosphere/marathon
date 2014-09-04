package mesosphere.marathon.state

import scala.collection.JavaConverters._
import scala.util.Try
import org.apache.mesos.{ Protos => mesos }
import mesosphere.marathon.Protos

case class Container(
    `type`: mesos.ContainerInfo.Type = mesos.ContainerInfo.Type.DOCKER,
    volumes: Seq[Container.Volume] = Nil,
    docker: Option[Container.Docker] = None) {
  def toProto(): mesos.ContainerInfo = {
    val builder = mesos.ContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .addAllVolumes(volumes.map(_.toProto).asJava)
    docker.foreach { d => builder.setDocker(d.toProto) }
    builder.build
  }
}

object Container {

  object Empty extends Container

  def apply(proto: mesos.ContainerInfo): Container =
    Container(
      `type` = proto.getType,
      volumes = proto.getVolumesList.asScala.map(Container.Volume.apply),
      docker = Try(Docker(proto.getDocker)).toOption
    )

  /**
    * Lossy conversion for backwards compatibility with deprecated
    * container representation.
    */
  def apply(proto: mesos.CommandInfo.ContainerInfo): Container =
    Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      docker = Some(Docker(proto.getImage, proto.getNetwork, proto.getPortMapping))
    )

  /**
    * Lossy conversion for backwards compatibility with deprecated
    * container representation.
    */
  def apply(proto: Protos.ContainerInfo): Container =
    Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      docker = Some(Docker(proto.getImage.toStringUtf8, proto.getNetwork, proto.getPortMapping))
    )

  /**
    * A volume mapping either from host to container or vice versa.
    * Both paths can either refer to a directory or a file.  Paths must be
    * absolute.
    */
  case class Volume(
      containerPath: String,
      hostPath: String,
      mode: mesos.Volume.Mode) {
    def toProto(): mesos.Volume =
      mesos.Volume.newBuilder
        .setContainerPath(containerPath)
        .setHostPath(hostPath)
        .setMode(mode)
        .build
  }

  object Volume {
    def apply(proto: mesos.Volume): Volume =
      Volume(
        containerPath = proto.getContainerPath,
        hostPath = Option(proto.getHostPath).getOrElse(""),
        mode = proto.getMode
      )
  }

  /**
    * Docker-specific container parameters.
    */
  case class Docker(image: String = "",
                    networkMode: Option[Mesos.ContainerInfo.DockerInfo.Network] = None)
    def toProto(): mesos.ContainerInfo.DockerInfo = {
      val builder = mesos.ContainerInfo.DockerInfo.newBuilder.setImage(image)
      networkMode.map { mode =>
        builder.setNetwork(mode)
      }
      builder.build
    }
  }

  object Docker {
    def apply(proto: mesos.ContainerInfo.DockerInfo): Docker =
      Docker(image = proto.getImage, proto.getNetwork)
  }
}
