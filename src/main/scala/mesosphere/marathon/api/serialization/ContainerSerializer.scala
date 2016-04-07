package mesosphere.marathon.api.serialization

import mesosphere.marathon.Protos
import mesosphere.marathon.core.externalvolume.ExternalVolumes
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
      .addAllVolumes(container.volumes.map(VolumeSerializer.toProto).asJava)
    container.docker.foreach { d => builder.setDocker(DockerSerializer.toProto(d)) }
    builder.build
  }

  def fromProto(proto: Protos.ExtendedContainerInfo): Container = {
    val maybeDocker = if (proto.hasDocker) Some(DockerSerializer.fromProto(proto.getDocker)) else None
    Container(
      `type` = proto.getType,
      volumes = proto.getVolumesList.asScala.map(Volume(_)).to[Seq],
      docker = maybeDocker
    )
  }

  def toMesos(container: Container): mesos.Protos.ContainerInfo = {
    val builder = mesos.Protos.ContainerInfo.newBuilder.setType(container.`type`)

    // first set container.docker because the external volume provider might depend on it
    // to set further values.
    container.docker.foreach { d => builder.setDocker(DockerSerializer.toMesos(d)) }

    container.volumes.foreach {
      case pv: PersistentVolume => // PersistentVolumes are handled differently
      case ev: ExternalVolume   => ExternalVolumes.build(builder, ev) // this also adds the volume
      case dv: DockerVolume     => builder.addVolumes(VolumeSerializer.toMesos(dv))
    }

    builder.build
  }
}

object VolumeSerializer {
  def toProto(volume: Volume): Protos.Volume = volume match {
    case p: PersistentVolume =>
      Protos.Volume.newBuilder()
        .setContainerPath(p.containerPath)
        .setPersistent(PersistentVolumeInfoSerializer.toProto(p.persistent))
        .setMode(p.mode)
        .build()

    case e: ExternalVolume =>
      Protos.Volume.newBuilder()
        .setContainerPath(e.containerPath)
        .setExternal(ExternalVolumeInfoSerializer.toProto(e.external))
        .setMode(e.mode)
        .build()

    case d: DockerVolume =>
      Protos.Volume.newBuilder()
        .setContainerPath(d.containerPath)
        .setHostPath(d.hostPath)
        .setMode(d.mode)
        .build()
  }

  /** Only DockerVolumes can be serialized into a Mesos Protobuf */
  def toMesos(volume: DockerVolume): mesos.Protos.Volume =
    mesos.Protos.Volume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build
}

object PersistentVolumeInfoSerializer {
  def toProto(info: PersistentVolumeInfo): Protos.Volume.PersistentVolumeInfo =
    Protos.Volume.PersistentVolumeInfo.newBuilder()
      .setSize(info.size)
      .build()
}

object ExternalVolumeInfoSerializer {
  def toProto(info: ExternalVolumeInfo): Protos.Volume.ExternalVolumeInfo = {
    val builder = Protos.Volume.ExternalVolumeInfo.newBuilder()
      .setName(info.name)
      .setProvider(info.provider)

    info.size.foreach(builder.setSize)
    info.options.map{
      case (key, value) => mesos.Protos.Label.newBuilder().setKey(key).setValue(value).build
    }.foreach(builder.addOptions)

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

  /**
    * Build the representation of the PortMapping as a Port proto.
    *
    * @param pm Docker Port Mapping
    * @param effectiveHostPort the effective Mesos Agent port allocated for
    *                          this port mapping.
    * @return the representation of the PortMapping as a Port proto to be
    *         included in the task's DiscoveryInfo
    */
  def toMesosPort(pm: PortMapping, effectiveHostPort: Int): mesos.Protos.Port = {
    val builder = mesos.Protos.Port.newBuilder
      .setNumber(effectiveHostPort)
      .setProtocol(pm.protocol)

    pm.name.foreach(builder.setName)

    if (pm.labels.nonEmpty) {
      val labelsBuilder = mesos.Protos.Labels.newBuilder
      pm.labels
        .map { case (key, value) => mesos.Protos.Label.newBuilder.setKey(key).setValue(value) }
        .foreach(labelsBuilder.addLabels)
      builder.setLabels(labelsBuilder)
    }

    builder.build
  }

}

object ParameterSerializer {
  def toMesos(param: Parameter): mesos.Protos.Parameter =
    mesos.Protos.Parameter.newBuilder
      .setKey(param.key)
      .setValue(param.value)
      .build

}
