package mesosphere.marathon.health

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp

case class Health(
    taskId: Task.Id,
    firstSuccess: Option[Timestamp] = None,
    lastSuccess: Option[Timestamp] = None,
    lastFailure: Option[Timestamp] = None,
    lastFailureCause: Option[String] = None,
    consecutiveFailures: Int = 0) {

  def alive: Boolean = lastSuccess.exists { successTime =>
    lastFailure.isEmpty || successTime > lastFailure.get
  }

  def update(result: HealthResult): Health = result match {
    case Healthy(_, _, time) => copy(
      firstSuccess = firstSuccess.orElse(Some(time)),
      lastSuccess = Some(time),
      consecutiveFailures = 0
    )
    case Unhealthy(_, _, cause, time) => copy(
      lastFailure = Some(time),
      lastFailureCause = Some(cause),
      consecutiveFailures = consecutiveFailures + 1
    )
  }
}
