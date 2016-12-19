package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.state
import org.apache.mesos.{ Protos => Mesos }

trait ContainerConversion extends HealthCheckConversion {

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

  implicit val containerWrites: Writes[state.Container, Container] = Writes { container =>

    implicit val credentialWrites: Writes[state.Container.Credential, DockerCredentials] = Writes { credentials =>
      DockerCredentials(credentials.principal, credentials.secret)
    }

    import Mesos.ContainerInfo.DockerInfo.{ Network => DockerNetworkMode }
    implicit val dockerNetworkInfoWrites: Writes[DockerNetworkMode, DockerNetwork] = Writes {
      case DockerNetworkMode.BRIDGE => DockerNetwork.Bridge
      case DockerNetworkMode.HOST => DockerNetwork.Host
      case DockerNetworkMode.USER => DockerNetwork.User
      case DockerNetworkMode.NONE => DockerNetwork.None
    }

    implicit val dockerDockerContainerWrites: Writes[state.Container.Docker, DockerContainer] = Writes { container =>
      DockerContainer(
        forcePullImage = container.forcePullImage,
        image = container.image,
        network = container.network.toRaml,
        parameters = container.parameters.toRaml,
        portMappings = container.portMappings.toRaml,
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
      Container(kind, docker = docker, appc = appc, volumes = container.volumes.toRaml)
    }
    container match {
      case docker: state.Container.Docker => create(EngineType.Docker, docker = Some(docker.toRaml[DockerContainer]))
      case mesos: state.Container.MesosDocker => create(EngineType.Mesos, docker = Some(mesos.toRaml[DockerContainer]))
      case mesos: state.Container.MesosAppC => create(EngineType.Mesos, appc = Some(mesos.toRaml[AppCContainer]))
      case mesos: state.Container.Mesos => create(EngineType.Mesos)
    }
  }
}

object ContainerConversion extends ContainerConversion
