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

  //enable maintenance mode
  lazy val MAINTENANCE_MODE = "maintenance_mode"

  lazy val availableFeatures = {
    val b = Map.newBuilder[String, String]

    b += VIPS -> "Enable networking VIPs UI"
    b += TASK_KILLING -> "Enable the optional TASK_KILLING state, available in Mesos 0.28 and later"
    b += EXTERNAL_VOLUMES -> "Enable external volumes support in Marathon"
    b += SECRETS -> "Enable support for secrets in Marathon (experimental)"
    b += GPU_RESOURCES -> "Enable support for GPU in Marathon (experimental)"

    if (BuildInfo.version < SemVer(1, 8, 0))
      b += MAINTENANCE_MODE -> "(on by default, has no effect) Decline offers from agents undergoing a maintenance window"

    b.result()
  }

  def description: String = {
    availableFeatures.map { case (name, description) => s"$name - $description" }.mkString(", ")
  }
}
