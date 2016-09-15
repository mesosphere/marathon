package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.raml.{ KVLabels, PodContainer }

import scala.collection.immutable.Map

trait ContainerConversion {
  implicit val fromAPIObjectToContainer: Converter[PodContainer,MesosContainer] = Converter { c: PodContainer =>
    MesosContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      env = c.environment.map(Converter(_)).getOrElse(PodDefinition.DefaultEnv),
      user = c.user,
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = c.labels.fold(Map.empty[String, String])(_.values),
      lifecycle = c.lifecycle
    )
  }

  implicit val fromContainerToAPIObject: Converter[MesosContainer,PodContainer] = Converter { c: MesosContainer =>
    PodContainer(
      name = c.name,
      exec = c.exec,
      resources = c.resources,
      endpoints = c.endpoints,
      image = c.image,
      environment = if(c.env.isEmpty) None else Some(Converter(c.env)),
      user = c.user,
      healthCheck = c.healthCheck,
      volumeMounts = c.volumeMounts,
      artifacts = c.artifacts,
      labels = if (c.labels.isEmpty) None else Some(KVLabels(c.labels)),
      lifecycle = c.lifecycle
    )
  }
}
