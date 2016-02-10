package mesosphere.marathon.health

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp

sealed trait HealthResult {
  def taskId: Task.Id
  def version: Timestamp
  def time: Timestamp
}

case class Healthy(
  taskId: Task.Id,
  version: Timestamp,
  time: Timestamp = Timestamp.now()) extends HealthResult

case class Unhealthy(
  taskId: Task.Id,
  version: Timestamp,
  cause: String,
  time: Timestamp = Timestamp.now()) extends HealthResult
