package mesosphere.marathon.core.pod

import mesosphere.marathon.plugin.{EnvVarValue, PathId, Secret}
import mesosphere.marathon.state.{RunnableSpec, Timestamp}

/**
  * A definition for Pods.
  */
case class PodDefinition(
  id: PathId,
  user: Option[String],
  env: Map[String, EnvVarValue],
  labels: Map[String, String],
  acceptedResourceRoles: Option[Set[String]],
  secrets: Map[String, Secret],
  containers: Iterable[MesosContainer],
  instances: Int,
  maxInstances: Option[Int],
  constraints: Iterable[Constraint],
  version: Timestamp,
  versionInfo: Option[VersionInfo],
  volumes: Iterable[Volume],
  networks: Iterable[Network]
) extends RunnableSpec {
  lazy val cpus: Double = containers.view.map(_.resources.cpus).sum
  lazy val mem: Double = containers.view.map(_.resources.mem).sum
  lazy val disk: Double = containers.view.flatMap(_.resources.disk).sum
  lazy val gpus: Int = containers.view.flatMap(_.resources.gpus).sum
}
