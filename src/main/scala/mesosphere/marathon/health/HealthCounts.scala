package mesosphere.marathon.health

case class HealthCounts(
  healthy: Int,
  unknown: Int,
  unhealthy: Int
)
