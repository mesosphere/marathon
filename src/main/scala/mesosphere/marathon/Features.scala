package mesosphere.marathon

object Features {

  //enable VIP UI
  val VIPS = "vips"

  //enable the optional task killing state
  val TASK_KILLING = "task_killing"

  //enable external volumes
  val EXTERNAL_VOLUMES = "external_volumes"

  lazy val availableFeatures = Map(
    VIPS -> "Enable networking VIPs UI",
    TASK_KILLING -> "Enable the optional TASK_KILLING state, available in Mesos 0.28 and later",
    EXTERNAL_VOLUMES -> "Enable external volumes support in Marathon"
  )

  def description: String = {
    availableFeatures.map { case (name, description) => s"$name - $description" }.mkString(", ")
  }
}
