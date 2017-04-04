package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.state.Parameter
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => Mesos }

trait ContainerConversion extends HealthCheckConversion with VolumeConversion with NetworkConversion {

  implicit val containerRamlWrites: Writes[MesosContainer, PodContainer] = Writes { c =>
    PodContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      environment = Raml.toRaml(c.env),
      user = c.user,
      healthCheck = c.healthCheck.toRaml[Option[HealthCheck]],
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = c.labels,
      lifecycle = c.lifecycle
    )
  }

  implicit val containerRamlReads: Reads[PodContainer, MesosContainer] = Reads { c =>
    MesosContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      env = Raml.fromRaml(c.environment),
      user = c.user,
      healthCheck = c.healthCheck.map(Raml.fromRaml(_)),
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = c.labels,
      lifecycle = c.lifecycle
    )
  }

  implicit val parameterWrites: Writes[state.Parameter, DockerParameter] = Writes { param =>
    DockerParameter(param.key, param.value)
  }

  implicit val containerWrites: Writes[state.Container, Container] = Writes { container =>

    implicit val credentialWrites: Writes[state.Container.Credential, DockerCredentials] = Writes { credentials =>
      DockerCredentials(credentials.principal, credentials.secret)
    }

    implicit val dockerDockerContainerWrites: Writes[state.Container.Docker, DockerContainer] = Writes { container =>
      DockerContainer(
        forcePullImage = container.forcePullImage,
        image = container.image,
        parameters = container.parameters.toRaml,
        privileged = container.privileged)
    }

    implicit val mesosDockerContainerWrites: Writes[state.Container.MesosDocker, DockerContainer] = Writes { container =>
      DockerContainer(
        image = container.image,
        credential = container.credential.toRaml,
        forcePullImage = container.forcePullImage)
    }

    implicit val mesosContainerWrites: Writes[state.Container.MesosAppC, AppCContainer] = Writes { container =>
      AppCContainer(container.image, container.id, container.labels, container.forcePullImage)
    }

    def create(kind: EngineType, docker: Option[DockerContainer] = None, appc: Option[AppCContainer] = None): Container = {
      Container(kind, docker = docker, appc = appc, volumes = container.volumes.toRaml,
        portMappings = Option(container.portMappings.toRaml) // this might need to be None, but we can't check networking here
      )
    }

    container match {
      case docker: state.Container.Docker => create(EngineType.Docker, docker = Some(docker.toRaml[DockerContainer]))
      case mesos: state.Container.MesosDocker => create(EngineType.Mesos, docker = Some(mesos.toRaml[DockerContainer]))
      case mesos: state.Container.MesosAppC => create(EngineType.Mesos, appc = Some(mesos.toRaml[AppCContainer]))
      case mesos: state.Container.Mesos => create(EngineType.Mesos)
    }
  }

  implicit val appContainerRamlReader: Reads[Container, state.Container] = Reads { container =>
    val volumes = container.volumes.map(Raml.fromRaml(_))
    val portMappings: Seq[state.Container.PortMapping] = container.portMappings.getOrElse(Nil).map(Raml.fromRaml(_))

    val result: state.Container = (container.`type`, container.docker, container.appc) match {
      case (EngineType.Docker, Some(docker), None) =>
        state.Container.Docker(
          volumes = volumes,
          image = docker.image,
          portMappings = portMappings, // assumed already normalized, see AppNormalization
          privileged = docker.privileged,
          parameters = docker.parameters.map(p => Parameter(p.key, p.value)),
          forcePullImage = docker.forcePullImage
        )
      case (EngineType.Mesos, Some(docker), None) =>
        state.Container.MesosDocker(
          volumes = volumes,
          image = docker.image,
          portMappings = portMappings, // assumed already normalized, see AppNormalization
          credential = docker.credential.map(c => state.Container.Credential(principal = c.principal, secret = c.secret)),
          forcePullImage = docker.forcePullImage
        )
      case (EngineType.Mesos, None, Some(appc)) =>
        state.Container.MesosAppC(
          volumes = volumes,
          image = appc.image,
          portMappings = portMappings,
          id = appc.id,
          labels = appc.labels,
          forcePullImage = appc.forcePullImage
        )
      case (EngineType.Mesos, None, None) =>
        state.Container.Mesos(
          volumes = volumes,
          portMappings = portMappings
        )
      case ct => throw SerializationFailedException(s"illegal container specification $ct")
    }
    result
  }

  implicit val containerTypeProtoToRamlWriter: Writes[org.apache.mesos.Protos.ContainerInfo.Type, EngineType] = Writes { ctype =>
    import org.apache.mesos.Protos.ContainerInfo.Type._
    ctype match {
      case MESOS => EngineType.Mesos
      case DOCKER => EngineType.Docker
      case badContainerType => throw new IllegalStateException(s"unsupported container type $badContainerType")
    }
  }

  implicit val appcProtoToRamlWriter: Writes[Protos.ExtendedContainerInfo.MesosAppCInfo, AppCContainer] = Writes { appc =>
    AppCContainer(
      image = appc.getImage,
      id = if (appc.hasId) Option(appc.getId) else AppCContainer.DefaultId,
      labels = appc.whenOrElse(_.getLabelsCount > 0, _.getLabelsList.flatMap(_.fromProto)(collection.breakOut), AppCContainer.DefaultLabels),
      forcePullImage = if (appc.hasForcePullImage) appc.getForcePullImage else AppCContainer.DefaultForcePullImage
    )
  }

  implicit val dockerParameterProtoRamlWriter: Writes[Mesos.Parameter, DockerParameter] = Writes { param =>
    DockerParameter(
      key = param.getKey,
      value = param.getValue
    )
  }

  implicit val dockerProtoToRamlWriter: Writes[Protos.ExtendedContainerInfo.DockerInfo, DockerContainer] = Writes { docker =>
    DockerContainer(
      credential = DockerContainer.DefaultCredential, // we don't store credentials in protobuf
      forcePullImage = docker.when(_.hasForcePullImage, _.getForcePullImage).getOrElse(DockerContainer.DefaultForcePullImage),
      image = docker.getImage,
      network = docker.when(_.hasOBSOLETENetwork, _.getOBSOLETENetwork.toRaml).orElse(DockerContainer.DefaultNetwork),
      parameters = docker.whenOrElse(_.getParametersCount > 0, _.getParametersList.map(_.toRaml)(collection.breakOut), DockerContainer.DefaultParameters),
      portMappings = Option.empty[Seq[ContainerPortMapping]].unless(
        docker.when(_.getOBSOLETENetwork != Mesos.ContainerInfo.DockerInfo.Network.HOST, _.getOBSOLETEPortMappingsList.map(_.toRaml)(collection.breakOut))),
      privileged = docker.when(_.hasPrivileged, _.getPrivileged).getOrElse(DockerContainer.DefaultPrivileged)
    )
  }

  implicit val dockerCredentialsProtoRamlWriter: Writes[Mesos.Credential, DockerCredentials] = Writes { cred =>
    DockerCredentials(
      principal = cred.getPrincipal,
      secret = cred.when(_.hasSecret, _.getSecret).orElse(DockerCredentials.DefaultSecret)
    )
  }

  implicit val mesosDockerProtoToRamlWriter: Writes[Protos.ExtendedContainerInfo.MesosDockerInfo, DockerContainer] = Writes { docker =>
    DockerContainer(
      credential = docker.when(_.hasCredential, _.getCredential.toRaml).orElse(DockerContainer.DefaultCredential),
      forcePullImage = docker.when(_.hasForcePullImage, _.getForcePullImage).getOrElse(DockerContainer.DefaultForcePullImage),
      image = docker.getImage
    // was never stored for mesos containers:
    // - network
    // - parameters
    // - portMappings
    // - privileged
    )
  }

  implicit val containerProtoToRamlWriter: Writes[Protos.ExtendedContainerInfo, Container] = Writes { container =>
    Container(
      `type` = container.when(_.hasType, _.getType.toRaml).getOrElse(Container.DefaultType),
      docker =
        container.collect {
          case x if x.hasDocker => x.getDocker.toRaml
          case x if x.hasMesosDocker => x.getMesosDocker.toRaml
        }.orElse(Container.DefaultDocker),
      appc = container.when(_.hasMesosAppC, _.getMesosAppC.toRaml).orElse(Container.DefaultAppc),
      volumes = container.whenOrElse(_.getVolumesCount > 0, _.getVolumesList.map(_.toRaml)(collection.breakOut), Container.DefaultVolumes),
      portMappings = container.collect {
        case x if !x.hasDocker || x.getDocker.getOBSOLETEPortMappingsCount == 0 =>
          container.getPortMappingsList.map(_.toRaml)(collection.breakOut)
      }
    )
  }
}

object ContainerConversion extends ContainerConversion
