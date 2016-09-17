package mesosphere.marathon.core.pod

import mesosphere.marathon.raml.{
  Image,
  Endpoint,
  Resources,
  MesosExec,
  HealthCheck,
  VolumeMount,
  Artifact,
  Lifecycle
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

