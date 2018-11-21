package mesosphere.marathon

object Features {

  //enable VIP UI
  lazy val VIPS = "vips"

  //enable the optional task killing state
  lazy val TASK_KILLING = "task_killing"

  //enable external volumes
  lazy val EXTERNAL_VOLUMES = "external_volumes"

  //enable GPUs
  lazy val GPU_RESOURCES = "gpu_resources"

  lazy val toggleableFeatures = {
    val b = Map.newBuilder[String, String]

    b += VIPS -> "Enable networking VIPs UI"
    b += TASK_KILLING -> "Enable the optional TASK_KILLING state, available in Mesos 0.28 and later"
    b += EXTERNAL_VOLUMES -> "Enable external volumes support in Marathon (experimental)"
    b += GPU_RESOURCES -> "Enable support for GPU in Marathon (experimental)"

    if (BuildInfo.version < SemVer(1, 9, 0))
      b += "secrets" -> "(enabled by plugin tagged as secrets, has no effect) Enable support for secrets in Marathon"

    b.result()
  }

  def description: String = {
    toggleableFeatures.map { case (name, description) => s"$name - $description" }.mkString(", ")
  }

  def empty = Features(Set.empty, false)
}

case class Features(toggled: Set[String], secretsEnabled: Boolean)
