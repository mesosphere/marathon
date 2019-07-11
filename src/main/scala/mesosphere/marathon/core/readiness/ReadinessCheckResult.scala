package mesosphere.marathon
package core.readiness

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import mesosphere.marathon.api.v2.json.JacksonSerializable
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.task.Task

case class ReadinessCheckResult(
    name: String,
    taskId: Task.Id,
    ready: Boolean,
    lastResponse: Option[HttpResponse]) extends JacksonSerializable[ReadinessCheckResult] {

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

  override def serializeWithJackson(gen: JsonGenerator, provider: SerializerProvider): Unit = {
    gen.writeStartObject()
    gen.writeObjectField("name", name)
    gen.writeObjectField("taskId", taskId.idString)
    gen.writeObjectField("ready", ready)
    gen.writeObjectField("lastResponse", lastResponse.orNull)
    gen.writeEndObject()
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
