package mesosphere.marathon
package api.serialization

import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork, HostNetwork, Network }
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos

object ContainerSerializer {
  def fromProto(proto: Protos.ExtendedContainerInfo): Container = {
    if (proto.hasDocker) {
      DockerSerializer.fromProto(proto)
    } else if (proto.hasMesosDocker) {
      MesosDockerSerializer.fromProto(proto)
    } else if (proto.hasMesosAppC) {
      MesosAppCSerializer.fromProto(proto)
    } else {
      val pms = proto.getPortMappingsList
      Container.Mesos(
        volumes = proto.getVolumesList.map(Volume(_))(collection.breakOut),
        portMappings = pms.map(PortMappingSerializer.fromProto)(collection.breakOut)
      )
    }
  }

  def toProto(container: Container): Protos.ExtendedContainerInfo = {
    val builder = Protos.ExtendedContainerInfo.newBuilder
      .addAllVolumes(container.volumes.map(VolumeSerializer.toProto).asJava)
      .addAllPortMappings(container.portMappings.map(PortMappingSerializer.toProto).asJava)

    container match {
      case _: Container.Mesos =>
        builder.setType(mesos.Protos.ContainerInfo.Type.MESOS)
      case dd: Container.Docker =>
        builder.setType(mesos.Protos.ContainerInfo.Type.DOCKER)
        builder.setDocker(DockerSerializer.toProto(dd))
      case md: Container.MesosDocker =>
        builder.setType(mesos.Protos.ContainerInfo.Type.MESOS)
        builder.setMesosDocker(MesosDockerSerializer.toProto(md))
      case ma: Container.MesosAppC =>
        builder.setType(mesos.Protos.ContainerInfo.Type.MESOS)
        builder.setMesosAppC(MesosAppCSerializer.toProto(ma))
    }

    builder.build
  }

  def toMesos(networks: Seq[Network], container: Container, mesosBridgeName: String): mesos.Protos.ContainerInfo = {

    def portMappingToMesos(mapping: PortMapping) = {
      val portBuilder = mesos.Protos.NetworkInfo.PortMapping.newBuilder()
        .setContainerPort(mapping.containerPort)
        .setProtocol(mapping.protocol)
      mapping.hostPort.foreach(portBuilder.setHostPort)
      portBuilder.build
    }

    val builder = mesos.Protos.ContainerInfo.newBuilder

    // First set type-specific values (for Docker) because the external volume provider
    // might depend on it to set further values (builder is passed down as arg below).
    container match {
      case _: Container.Mesos =>
        builder.setType(mesos.Protos.ContainerInfo.Type.MESOS)
      case dd: Container.Docker =>
        builder.setType(mesos.Protos.ContainerInfo.Type.DOCKER)
        builder.setDocker(DockerSerializer.toMesos(dd, networks))
        // docker user network names are the only things passed in the NetworkInfo
        networks.collectFirst {
          case n: ContainerNetwork => mesos.Protos.NetworkInfo.newBuilder.setName(n.name)
        }.foreach(builder.addNetworkInfos)
      case md: Container.MesosDocker =>
        builder.setType(mesos.Protos.ContainerInfo.Type.MESOS)
        builder.setMesos(MesosDockerSerializer.toMesos(md))
      case ma: Container.MesosAppC =>
        builder.setType(mesos.Protos.ContainerInfo.Type.MESOS)
        builder.setMesos(MesosAppCSerializer.toMesos(ma))
    }

    container.volumes.foreach {
      case _: PersistentVolume => // PersistentVolumes are handled differently
      case ev: ExternalVolume => ExternalVolumes.build(builder, ev) // this also adds the volume
      case dv: DockerVolume => builder.addVolumes(VolumeSerializer.toMesos(dv))
      case _: SecretVolume => // SecretVolumes are handled differently
    }

    // only UCR containers have NetworkInfo's generated this way
    if (container.docker.isEmpty) {
      networks.toIterator
        .filter(_ != HostNetwork)
        .map { network =>
          val (networkName, networkLabels) = network match {
            case cnet: ContainerNetwork => cnet.name -> cnet.labels.toMesosLabels
            case bnet: BridgeNetwork => mesosBridgeName -> bnet.labels.toMesosLabels
            case unsupported => throw new IllegalStateException(s"unsupported networking mode $unsupported")
          }

          // if hostPort is specified, a SINGLE networkName is required.
          // If it is empty then we've already validated that there is only one container network
          def qualifiedPortMapping(mapping: Container.PortMapping) =
            mapping.hostPort.nonEmpty && mapping.networkNames.forall(_ == networkName)

          val portMappings = container.portMappings.withFilter(qualifiedPortMapping)

          mesos.Protos.NetworkInfo.newBuilder()
            .addIpAddresses(mesos.Protos.NetworkInfo.IPAddress.getDefaultInstance)
            .setLabels(networkLabels)
            .setName(networkName)
            .addAllPortMappings(portMappings.map(portMappingToMesos).asJava)
            .build
        }
        .foreach(builder.addNetworkInfos)
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

    case s: SecretVolume =>
      Protos.Volume.newBuilder()
        .setContainerPath(s.containerPath)
        .setSecret(
          Protos.Volume.SecretVolumeInfo.newBuilder().setSecret(s.secret).build()
        )
        .setMode(s.mode)
        .build()
  }

  /**
    * Only DockerVolumes can be serialized into a Mesos Protobuf.
    * see mesosphere.marathon.core.externalvolume.impl.providers.DVDIProvider.
    */
  def toMesos(volume: DockerVolume): mesos.Protos.Volume =
    mesos.Protos.Volume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build
}

object PersistentVolumeInfoSerializer {
  def toProto(info: PersistentVolumeInfo): Protos.Volume.PersistentVolumeInfo = {
    val builder = Protos.Volume.PersistentVolumeInfo.newBuilder()
    builder.setSize(info.size)
    info.`type` match {
      case DiskType.Root =>
        ()
      case DiskType.Path =>
        builder.setType(mesos.Protos.Resource.DiskInfo.Source.Type.PATH)
      case DiskType.Mount =>
        builder.setType(mesos.Protos.Resource.DiskInfo.Source.Type.MOUNT)
    }
    builder.addAllConstraints(info.constraints.asJava)
    info.maxSize.foreach(builder.setMaxSize)

    builder.build()
  }

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
  def fromProto(proto: Protos.ExtendedContainerInfo): Container.Docker = {
    val d = proto.getDocker
    val pms = proto.getPortMappingsList
    Container.Docker(
      volumes = proto.getVolumesList.map(Volume(_))(collection.breakOut),
      image = d.getImage,
      portMappings = pms.map(PortMappingSerializer.fromProto)(collection.breakOut),
      privileged = d.getPrivileged,
      parameters = d.getParametersList.map(Parameter(_))(collection.breakOut),
      forcePullImage = if (d.hasForcePullImage) d.getForcePullImage else false
    )
  }

  def toProto(docker: Container.Docker): Protos.ExtendedContainerInfo.DockerInfo = {
    Protos.ExtendedContainerInfo.DockerInfo.newBuilder
      .setImage(docker.image)
      .setPrivileged(docker.privileged)
      .addAllParameters(docker.parameters.map(ParameterSerializer.toMesos).asJava)
      .setForcePullImage(docker.forcePullImage)
      .build
  }

  def toMesos(docker: Container.Docker, networks: Seq[Network]): mesos.Protos.ContainerInfo.DockerInfo = {
    import mesos.Protos.ContainerInfo.DockerInfo
    val builder = DockerInfo.newBuilder

    builder.setImage(docker.image)
    docker.portMappings.foreach(mapping => builder.addAllPortMappings(PortMappingSerializer.toMesos(mapping).asJava))
    builder.setPrivileged(docker.privileged)
    builder.addAllParameters(docker.parameters.map(ParameterSerializer.toMesos).asJava)
    builder.setForcePullImage(docker.forcePullImage)
    networks.collect {
      case _: ContainerNetwork => DockerInfo.Network.USER
      case _: BridgeNetwork => DockerInfo.Network.BRIDGE
      case HostNetwork => DockerInfo.Network.HOST // it's the default, but we include here for posterity
      case unsupported => throw SerializationFailedException(s"unsupported docker network type $unsupported")
    }.foreach(builder.setNetwork)
    builder.build
  }
}

object PortMappingSerializer {
  def toProto(mapping: Container.PortMapping): Protos.ExtendedContainerInfo.PortMapping = {
    val builder = Protos.ExtendedContainerInfo.PortMapping.newBuilder
      .setContainerPort(mapping.containerPort)
      .setProtocol(mapping.protocol)
      .setServicePort(mapping.servicePort)

    mapping.hostPort.foreach(builder.setHostPort)
    mapping.name.foreach(builder.setName)
    mapping.labels.toProto.foreach(builder.addLabels)
    mapping.networkNames.foreach(builder.addNetworkNames)

    builder.build
  }

  def fromProto(proto: Protos.ExtendedContainerInfo.PortMapping): PortMapping =
    PortMapping(
      proto.getContainerPort,
      if (proto.hasHostPort) Some(proto.getHostPort) else None,
      proto.getServicePort,
      proto.getProtocol,
      if (proto.hasName) Some(proto.getName) else None,
      proto.getLabelsList.map { p => p.getKey -> p.getValue }(collection.breakOut),
      proto.getNetworkNamesList.toList
    )

  def toMesos(mapping: Container.PortMapping): Seq[mesos.Protos.ContainerInfo.DockerInfo.PortMapping] = {
    def mesosPort(protocol: String, hostPort: Int) = {
      mesos.Protos.ContainerInfo.DockerInfo.PortMapping.newBuilder
        .setContainerPort (mapping.containerPort)
        .setHostPort(hostPort)
        .setProtocol(protocol)
        .build
    }
    // we specifically don't want to generate a mesos proto port mapping here if there's no hostport:
    //
    // 1. hostport is required for the mesos proto, because...
    // 2. the mapping is used to set up NAT or port-forwarding from hostport to containerport; noop if hostport is None
    mapping.hostPort.fold(Seq.empty[mesos.Protos.ContainerInfo.DockerInfo.PortMapping]) { hp =>
      mapping.protocol.split(',').map(mesosPort(_, hp)).toList
    }
  }

  /**
    * Build the representation of the PortMapping as a Port proto.
    *
    * @param name The Port Mapping name
    * @param labels The labels to be attached to the Port proto
    * @param protocol The labels to be attached to the Port proto
    * @param effectivePort the effective Mesos Agent port allocated for
    *                          this port mapping.
    * @return the representation of provided input to be included in the task's DiscoveryInfo
    */
  def toMesosPort(name: Option[String], labels: Map[String, String], protocol: String, effectivePort: Int): mesos.Protos.Port = {
    val builder = mesos.Protos.Port.newBuilder
      .setNumber(effectivePort)
      .setProtocol(protocol)

    name.foreach(builder.setName)

    if (labels.nonEmpty) {
      builder.setLabels(labels.toMesosLabels)
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

object CredentialSerializer {
  def fromMesos(credential: mesos.Protos.Credential): Container.Credential = {
    Container.Credential(
      credential.getPrincipal,
      if (credential.hasSecret) Some(credential.getSecret) else None
    )
  }

  def toMesos(credential: Container.Credential): mesos.Protos.Credential = {
    val builder = mesos.Protos.Credential.newBuilder
      .setPrincipal(credential.principal)
    credential.secret.foreach(builder.setSecret)
    builder.build
  }
}

object DockerPullConfigSerializer {
  def fromProto(pullConfig: Protos.ExtendedContainerInfo.DockerInfo.ImagePullConfig): Container.DockerPullConfig = {
    pullConfig.when(_.getType == Protos.ExtendedContainerInfo.DockerInfo.ImagePullConfig.Type.SECRET, _ => {
      pullConfig.when(_.hasSecret, _.getSecret).flatMap { secret =>
        secret.when(_.hasType, _.getType).flatMap {
          case mesos.Protos.Secret.Type.REFERENCE =>
            secret.when(_.hasReference, _.getReference.getName).map(Container.DockerPullConfig)
          case _ => None
        }
      }
    }).flatten match {
      case Some(deserializedPullConfig) => deserializedPullConfig
      case _ =>
        throw SerializationFailedException(s"Failed to deserialize a docker pull config: $pullConfig")
    }
  }

  def toProto(pullConfig: Container.DockerPullConfig): Protos.ExtendedContainerInfo.DockerInfo.ImagePullConfig = pullConfig match {
    case Container.DockerPullConfig(secret) =>
      val builder = Protos.ExtendedContainerInfo.DockerInfo.ImagePullConfig.newBuilder
      builder.setType(Protos.ExtendedContainerInfo.DockerInfo.ImagePullConfig.Type.SECRET)
      val secretProto = SecretSerializer.toSecretReference(secret)
      builder.setSecret(secretProto)
      builder.build
  }

  def toMesos(pullConfig: Container.DockerPullConfig): mesos.Protos.Secret = pullConfig match {
    case Container.DockerPullConfig(secret) =>
      SecretSerializer.toSecretReference(secret)
  }
}

object MesosDockerSerializer {
  def fromProto(proto: Protos.ExtendedContainerInfo): Container.MesosDocker = {
    val d = proto.getMesosDocker
    val pms = proto.getPortMappingsList
    Container.MesosDocker(
      volumes = proto.getVolumesList.map(Volume(_))(collection.breakOut),
      portMappings = pms.map(PortMappingSerializer.fromProto)(collection.breakOut),
      image = d.getImage,
      credential = if (d.hasDeprecatedCredential) Some(CredentialSerializer.fromMesos(d.getDeprecatedCredential)) else None,
      pullConfig = if (d.hasPullConfig) Some(DockerPullConfigSerializer.fromProto(d.getPullConfig)) else None,
      forcePullImage = if (d.hasForcePullImage) d.getForcePullImage else false
    )
  }

  def toProto(docker: Container.MesosDocker): Protos.ExtendedContainerInfo.MesosDockerInfo = {
    val builder = Protos.ExtendedContainerInfo.MesosDockerInfo.newBuilder
      .setImage(docker.image)
      .setForcePullImage(docker.forcePullImage)

    docker.credential.foreach { credential =>
      builder.setDeprecatedCredential(CredentialSerializer.toMesos(credential))
    }
    docker.pullConfig.foreach { pullConfig =>
      builder.setPullConfig(DockerPullConfigSerializer.toProto(pullConfig))
    }

    builder.build
  }

  def toMesos(container: Container.MesosDocker): mesos.Protos.ContainerInfo.MesosInfo = {
    val dockerBuilder = mesos.Protos.Image.Docker.newBuilder
      .setName(container.image)

    container.credential.foreach { credential =>
      dockerBuilder.setCredential(CredentialSerializer.toMesos(credential))
    }
    container.pullConfig.foreach { pullConfig =>
      dockerBuilder.setConfig(DockerPullConfigSerializer.toMesos(pullConfig))
    }

    val imageBuilder = mesos.Protos.Image.newBuilder
      .setType(mesos.Protos.Image.Type.DOCKER)
      .setDocker(dockerBuilder)
      .setCached(!container.forcePullImage)

    mesos.Protos.ContainerInfo.MesosInfo.newBuilder.setImage(imageBuilder).build
  }
}

object MesosAppCSerializer {
  def fromProto(proto: Protos.ExtendedContainerInfo): Container.MesosAppC = {
    val appc = proto.getMesosAppC
    val pms = proto.getPortMappingsList
    Container.MesosAppC(
      volumes = proto.getVolumesList.map(Volume(_))(collection.breakOut),
      portMappings = pms.map(PortMappingSerializer.fromProto)(collection.breakOut),
      image = appc.getImage,
      id = if (appc.hasId) Some(appc.getId) else None,
      labels = appc.getLabelsList.map { p => p.getKey -> p.getValue }(collection.breakOut),
      forcePullImage = if (appc.hasForcePullImage) appc.getForcePullImage else false
    )
  }

  def toProto(appc: Container.MesosAppC): Protos.ExtendedContainerInfo.MesosAppCInfo = {
    val builder = Protos.ExtendedContainerInfo.MesosAppCInfo.newBuilder
      .setImage(appc.image)
      .setForcePullImage(appc.forcePullImage)

    appc.id.foreach(builder.setId)
    appc.labels.toProto.foreach(builder.addLabels)

    builder.build
  }

  def toMesos(container: Container.MesosAppC): mesos.Protos.ContainerInfo.MesosInfo = {
    val appcBuilder = mesos.Protos.Image.Appc.newBuilder
      .setName(container.image)
    container.id.foreach(appcBuilder.setId)
    appcBuilder.setLabels(container.labels.toMesosLabels)

    val imageBuilder = mesos.Protos.Image.newBuilder
      .setType(mesos.Protos.Image.Type.APPC)
      .setAppc(appcBuilder)
      .setCached(!container.forcePullImage)

    mesos.Protos.ContainerInfo.MesosInfo.newBuilder.setImage(imageBuilder).build
  }
}
