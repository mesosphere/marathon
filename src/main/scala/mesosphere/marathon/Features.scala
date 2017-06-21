package mesosphere.marathon

object Features {

  //enable VIP UI
  lazy val VIPS = "vips"

  //enable the optional task killing state
  lazy val TASK_KILLING = "task_killing"

  //enable external volumes
  lazy val EXTERNAL_VOLUMES = "external_volumes"

  //enable secrets
  lazy val SECRETS = "secrets"

  //enable GPUs
  lazy val GPU_RESOURCES = "gpu_resources"

  lazy val availableFeatures = Map(
    VIPS -> "Enable networking VIPs UI",
    TASK_KILLING -> "Enable the optional TASK_KILLING state, available in Mesos 0.28 and later",
    EXTERNAL_VOLUMES -> "Enable external volumes support in Marathon",
    SECRETS -> "Enable support for secrets in Marathon (experimental)",
    GPU_RESOURCES -> "Enable support for GPU in Marathon (experimental)"
  )

  def description: String = {
    availableFeatures.map { case (name, description) => s"$name - $description" }.mkString(", ")
  }
}
