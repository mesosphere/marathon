package mesosphere.marathon.core.pod

import mesosphere.marathon.raml.{
  Image,
  Endpoint,
  Resources,
  MesosExec,
  HealthCheck,
  VolumeMount,
  Artifact,
  Lifecycle,
  PodContainer,
  KVLabels
}
import mesosphere.marathon.state
import mesosphere.marathon.plugin.ContainerSpec
import scala.collection.immutable.Map

case class MesosContainer(
  name: String,
  exec: Option[MesosExec] = None,
  resources: Resources,
  endpoints: scala.collection.immutable.Seq[Endpoint] = Nil,
  image: Option[Image] = None,
  env: Map[String, state.EnvVarValue] = Map.empty,
  user: Option[String] = None,
  healthCheck: Option[HealthCheck] = None, //TODO(PODS): use health.HealthCheck
  volumeMounts: scala.collection.immutable.Seq[VolumeMount] = Nil,
  artifacts: scala.collection.immutable.Seq[Artifact] = Nil, //TODO(PODS): use FetchUri
  labels: Map[String, String] = Map.empty,
  lifecycle: Option[Lifecycle] = None) extends ContainerSpec

object MesosContainer {

  import mesosphere.marathon.api.v2.conversion._

  def apply(c: PodContainer): MesosContainer = MesosContainer(
    name = c.name,
    exec = c.exec,
    resources = c.resources,
    endpoints = c.endpoints,
    image = c.image,
    env = c.environment.flatMap(Converter.convert(_)).getOrElse(Map.empty[String, state.EnvVarValue]),
    user = c.user,
    healthCheck = c.healthCheck,
    volumeMounts = c.volumeMounts,
    artifacts = c.artifacts,
    labels = c.labels.fold(Map.empty[String, String])(_.values),
    lifecycle = c.lifecycle
  )

  def toPodContainer(c: MesosContainer): PodContainer = PodContainer(
    name = c.name,
    exec = c.exec,
    resources = c.resources,
    endpoints = c.endpoints,
    image = c.image,
    environment = Converter.convert(c.env),
    user = c.user,
    healthCheck = c.healthCheck,
    volumeMounts = c.volumeMounts,
    artifacts = c.artifacts,
    labels = if (c.labels.isEmpty) None else Some(KVLabels(c.labels)),
    lifecycle = c.lifecycle
  )
}

