package mesosphere.marathon.health

import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.state.Timestamp

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonInclude.Include

case class Health(
    taskId: String,
    firstSuccess: Option[Timestamp] = None,
    lastSuccess: Option[Timestamp] = None,
    lastFailure: Option[Timestamp] = None,
    @FieldJsonInclude(Include.NON_NULL) lastFailureCause: Option[String] = None,
    consecutiveFailures: Int = 0) {

  @JsonProperty
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
