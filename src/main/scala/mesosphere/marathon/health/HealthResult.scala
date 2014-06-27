package mesosphere.marathon.health

import mesosphere.marathon.state.Timestamp

sealed trait HealthResult {
  val taskId: String
  val time: Timestamp
}

case class Healthy(
  taskId: String,
  time: Timestamp = Timestamp.now()) extends HealthResult

case class Unhealthy(
  taskId: String,
  time: Timestamp = Timestamp.now(),
  cause: String) extends HealthResult
