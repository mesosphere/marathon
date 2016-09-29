package mesosphere.marathon.raml

import mesosphere.marathon.core.pod.MesosContainer

trait ContainerConversion {
  implicit val containerRamlWrites: Writes[MesosContainer, PodContainer] = Writes { c =>
    PodContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      environment = Raml.toRaml(c.env),
      user = c.user,
      healthCheck = c.healthCheck,
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
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = c.labels,
      lifecycle = c.lifecycle
    )
  }
}

object ContainerConversion extends ContainerConversion
