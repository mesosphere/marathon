package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.raml.{ KVLabels, PodContainer }
import mesosphere.marathon.state

import scala.collection.immutable.Map

trait ContainerConversion {
  implicit val fromAPIObjectToContainer = Converter { c: PodContainer =>
    Some(MesosContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      env = c.environment.flatMap(Converter(_)).getOrElse(Map.empty[String, state.EnvVarValue]),
      user = c.user,
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = c.labels.fold(Map.empty[String, String])(_.values),
      lifecycle = c.lifecycle
    ))
  }

  implicit val fromContainerToAPIObject = Converter { c: MesosContainer =>
    Some(PodContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      environment = Converter(c.env),
      user = c.user,
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = if (c.labels.isEmpty) None else Some(KVLabels(c.labels)),
      lifecycle = c.lifecycle
    ))
  }
}
