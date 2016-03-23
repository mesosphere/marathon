package mesosphere.marathon

object Features {

  //enable VIP UI
  val VIPS = "vips"

  //enable the optional task killing state
  val TASK_KILLING = "task_killing"

  lazy val availableFeatures = Map(
    VIPS -> "Enable networking VIPs UI",
    TASK_KILLING -> "Enable the optional TASK_KILLING state, available in Mesos 0.28 and later"
  )

  def description: String = {
    availableFeatures.map { case (name, description) => s"$name - $description" }.mkString(", ")
  }
}
