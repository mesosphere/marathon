package mesosphere.marathon.core.pod

import mesosphere.marathon.plugin.EnvVarValue

case class MesosContainer(
  name: String,
  command: Option[Command],
  resources: Resources,
  endpoints: Iterable[Endpoint],
  image: Option[Image],
  env: Map[String, EnvVarValue],
  user: Option[String],
  healthCheck: HealthCheck,
  volumeMounts: Iterable[VolumeMount],
  artifacts: Iterable[Artifact]
)
