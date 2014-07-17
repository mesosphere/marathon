package mesosphere.marathon.health

import mesosphere.marathon.state.Timestamp

sealed trait HealthResult {
  def taskId: String
  def version: String
  def time: Timestamp
}

case class Healthy(
  taskId: String,
  version: String,
  time: Timestamp = Timestamp.now()) extends HealthResult

case class Unhealthy(
  taskId: String,
  version: String,
  cause: String,
  time: Timestamp = Timestamp.now()) extends HealthResult
