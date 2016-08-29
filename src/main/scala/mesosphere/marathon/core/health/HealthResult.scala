package mesosphere.marathon.core.health

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.Timestamp

sealed trait HealthResult {
  def taskId: Instance.Id
  def version: Timestamp
  def time: Timestamp
}

case class Healthy(
  taskId: Instance.Id,
  version: Timestamp,
  time: Timestamp = Timestamp.now()) extends HealthResult

case class Unhealthy(
  taskId: Instance.Id,
  version: Timestamp,
  cause: String,
  time: Timestamp = Timestamp.now()) extends HealthResult
