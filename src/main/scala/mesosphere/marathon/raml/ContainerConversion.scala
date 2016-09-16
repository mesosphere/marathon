package mesosphere.marathon.raml

import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }

import scala.collection.immutable.Map

trait ContainerConversion {
  implicit val containerRamlWrites: Writes[MesosContainer, PodContainer] = Writes { c =>
    PodContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      environment = if (c.env.isEmpty) None else Some(Raml.toRaml(c.env)),
      user = c.user,
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = if (c.labels.isEmpty) None else Some(KVLabels(c.labels)),
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
      env = c.environment.map(Raml.fromRaml(_)).getOrElse(PodDefinition.DefaultEnv),
      user = c.user,
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = c.labels.fold(Map.empty[String, String])(_.values),
      lifecycle = c.lifecycle
    )
  }
}

object ContainerConversion extends ContainerConversion
