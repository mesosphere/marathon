package mesosphere.marathon.api.serialization

import mesosphere.marathon.Protos
import mesosphere.marathon.core.volume.VolumesModule
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state._
import org.apache.mesos

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object ContainerSerializer {
  def toProto(container: Container): Protos.ExtendedContainerInfo = {
    val builder = Protos.ExtendedContainerInfo.newBuilder
      .setType(container.`type`)
      .addAllVolumes(container.volumes.map(_.toProto).asJava)
    container.docker.foreach { d => builder.setDocker(DockerSerializer.toProto(d)) }
    builder.build
  }

  def fromProto(proto: Protos.ExtendedContainerInfo): Container = {
    val maybeDocker = if (proto.hasDocker) Some(DockerSerializer.fromProto(proto.getDocker)) else None
    Container(
      `type` = proto.getType,
      volumes = proto.getVolumesList.asScala.map(Volume.fromProto(_)).to[Seq],
      docker = maybeDocker
    )
  }

  def toMesos(container: Container): mesos.Protos.ContainerInfo = {
    // we only serialize non-agent-local volumes into a Mesos Protobuf. the details are
    // left up to individual volume provider implementations.
    var builder = mesos.Protos.ContainerInfo.newBuilder
      .setType(container.`type`)

    container.volumes.foreach { v =>
      builder = VolumesModule.builders.containerInfo(v, builder).getOrElse(builder)
    }

    container.docker.foreach { d => builder.setDocker(DockerSerializer.toMesos(d)) }
    builder.build
  }
}

object DockerSerializer {
  def toProto(docker: Container.Docker): Protos.ExtendedContainerInfo.DockerInfo = {
    val builder = Protos.ExtendedContainerInfo.DockerInfo.newBuilder
      .setImage(docker.image)
      .setPrivileged(docker.privileged)
      .addAllParameters(docker.parameters.map(ParameterSerializer.toMesos).asJava)
      .setForcePullImage(docker.forcePullImage)

    docker.network foreach builder.setNetwork

    docker.portMappings.foreach { pms =>
      builder.addAllPortMappings(pms.map(PortMappingSerializer.toProto).asJava)
    }

    builder.build
  }

  def fromProto(proto: Protos.ExtendedContainerInfo.DockerInfo): Docker =
    Docker(
      image = proto.getImage,
      network = if (proto.hasNetwork) Some(proto.getNetwork) else None,
      portMappings = {
        val pms = proto.getPortMappingsList.asScala

        if (pms.isEmpty) None
        else Some(pms.map(PortMappingSerializer.fromProto).to[Seq])
      },
      privileged = proto.getPrivileged,
      parameters = proto.getParametersList.asScala.map(Parameter(_)).to[Seq],
      forcePullImage = if (proto.hasForcePullImage) proto.getForcePullImage else false
    )

  def toMesos(docker: Container.Docker): mesos.Protos.ContainerInfo.DockerInfo = {
    val builder = mesos.Protos.ContainerInfo.DockerInfo.newBuilder

    builder.setImage(docker.image)

    docker.network foreach builder.setNetwork

    docker.portMappings.foreach { pms =>
      builder.addAllPortMappings(pms.flatMap(PortMappingSerializer.toMesos).asJava)
    }

    builder.setPrivileged(docker.privileged)

    builder.addAllParameters(docker.parameters.map(ParameterSerializer.toMesos).asJava)

    builder.setForcePullImage(docker.forcePullImage)

    builder.build
  }
}

object PortMappingSerializer {
  def toProto(mapping: Container.Docker.PortMapping): Protos.ExtendedContainerInfo.DockerInfo.PortMapping = {
    val builder = Protos.ExtendedContainerInfo.DockerInfo.PortMapping.newBuilder
      .setContainerPort(mapping.containerPort)
      .setHostPort(mapping.hostPort)
      .setProtocol(mapping.protocol)
      .setServicePort(mapping.servicePort)

    mapping.name.foreach(builder.setName)
    mapping.labels
      .map { case (key, value) => mesos.Protos.Label.newBuilder.setKey(key).setValue(value).build }
      .foreach(builder.addLabels)

    builder.build
  }

  def fromProto(proto: Protos.ExtendedContainerInfo.DockerInfo.PortMapping): PortMapping =
    PortMapping(
      proto.getContainerPort,
      proto.getHostPort,
      proto.getServicePort,
      proto.getProtocol,
      if (proto.hasName) Some(proto.getName) else None,
      proto.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap
    )

  def toMesos(mapping: Container.Docker.PortMapping): Seq[mesos.Protos.ContainerInfo.DockerInfo.PortMapping] = {
    def mesosPort(protocol: String) = {
      mesos.Protos.ContainerInfo.DockerInfo.PortMapping.newBuilder
        .setContainerPort (mapping.containerPort)
        .setHostPort(mapping.hostPort)
        .setProtocol(protocol)
        .build
    }
    mapping.protocol.split(',').map(mesosPort).toList
  }

}

object ParameterSerializer {
  def toMesos(param: Parameter): mesos.Protos.Parameter =
    mesos.Protos.Parameter.newBuilder
      .setKey(param.key)
      .setValue(param.value)
      .build

}
