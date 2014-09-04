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
      docker = Some(Docker(proto.getImage))
    )

  /**
    * Lossy conversion for backwards compatibility with deprecated
    * container representation.
    */
  def apply(proto: Protos.ContainerInfo): Container =
    Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      docker = Some(Docker(proto.getImage.toStringUtf8))
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
  case class Docker(
      image: String = "",
      network: Option[mesos.ContainerInfo.DockerInfo.NetworkMode] = None,
      portMappings: Seq[Docker.PortMapping] = Nil) {
    def toProto(): mesos.ContainerInfo.DockerInfo = {
      val builder = mesos.ContainerInfo.DockerInfo.newBuilder.setImage(image)
      network foreach builder.setNetwork
      builder.addAllPortMappings(portMappings.map(_.toProto).asJava)
      builder.build
    }
  }

  object Docker {
    def apply(proto: mesos.ContainerInfo.DockerInfo): Docker =
      Docker(
        image = proto.getImage,
        if (proto.hasNetwork) Some(proto.getNetwork) else None,
        proto.getPortMappingsList.asScala.map(PortMapping.apply)
      )

    /**
      * @param containerPort The container port to expose
      * @param hostPort  The host port to bind
      * @param protocol  Layer 4 protocol to expose (i.e. tcp, udp).
      */
    case class PortMapping(
        containerPort: Int,
        hostPort: Int,
        protocol: String) {
      def toProto(): mesos.ContainerInfo.DockerInfo.PortMapping = {
        mesos.ContainerInfo.DockerInfo.PortMapping.newBuilder
          .setContainerPort(containerPort)
          .setHostPort(hostPort)
          .setProtocol(protocol)
          .build
      }
    }

    object PortMapping {
      def apply(proto: mesos.ContainerInfo.DockerInfo.PortMapping): PortMapping =
        PortMapping(
          proto.getContainerPort,
          proto.getHostPort,
          proto.getProtocol
        )

    }

  }

}
