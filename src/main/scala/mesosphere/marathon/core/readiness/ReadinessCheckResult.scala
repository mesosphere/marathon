package mesosphere.marathon.core.readiness

import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.task.Task

case class ReadinessCheckResult(
    name: String,
    taskId: Task.Id,
    ready: Boolean,
    lastResponse: Option[HttpResponse]) {

  def summary: String = {
    val responseSummary = lastResponse.fold("") { response =>
      s" (${response.status})"
    }

    def shorten(str: String): String = {
      if (str.length > ReadinessCheckResult.SummaryBodyLength)
        str.take(ReadinessCheckResult.SummaryBodyLength - 3) + "..."
      else str
    }

    val bodySummary = lastResponse.fold("") { response =>
      s": ${shorten(response.body)}"
    }

    s"${if (ready) "READY" else "NOT READY"}$responseSummary returned by $taskId readiness check '$name'$bodySummary"
  }
}

object ReadinessCheckResult {
  private val SummaryBodyLength = 40

  def forSpecAndResponse(check: ReadinessCheckSpec, response: HttpResponse): ReadinessCheckResult = {
    ReadinessCheckResult(
      name = check.checkName,
      taskId = check.taskId,
      ready = check.httpStatusCodesForReady(response.status),
      lastResponse = if (check.preserveLastResponse) Some(response) else None
    )
  }
}

case class HttpResponse(status: Int, contentType: String, body: String)
