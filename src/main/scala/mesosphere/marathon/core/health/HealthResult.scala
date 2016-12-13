package mesosphere.marathon.core.health

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.Timestamp

sealed trait HealthResult {
  def instanceId: Instance.Id
  def version: Timestamp
  def time: Timestamp
  def publishEvent: Boolean
}

case class Healthy(
  instanceId: Instance.Id,
  version: Timestamp,
  time: Timestamp = Timestamp.now(),
  publishEvent: Boolean = true) extends HealthResult

case class Unhealthy(
  instanceId: Instance.Id,
  version: Timestamp,
  cause: String,
  time: Timestamp = Timestamp.now(),
  publishEvent: Boolean = true) extends HealthResult
