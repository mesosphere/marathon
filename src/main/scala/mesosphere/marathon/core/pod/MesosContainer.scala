package mesosphere.marathon
package core.pod

import mesosphere.marathon.plugin.ContainerSpec
import mesosphere.marathon.raml.{ Artifact, Endpoint, Image, Lifecycle, MesosExec, Resources }

import scala.collection.immutable.Map

case class MesosContainer(
  name: String,
  exec: Option[MesosExec] = None,
  resources: Resources,
  endpoints: scala.collection.immutable.Seq[Endpoint] = Nil,
  image: Option[Image] = None,
  env: Map[String, state.EnvVarValue] = Map.empty,
  user: Option[String] = None,
  healthCheck: Option[core.health.MesosHealthCheck] = None,
  volumeMounts: Seq[VolumeMount] = Nil,
  artifacts: Seq[Artifact] = Nil, //TODO(PODS): use FetchUri
  labels: Map[String, String] = Map.empty,
  lifecycle: Option[Lifecycle] = None,
  tty: Option[Boolean] = None) extends ContainerSpec
