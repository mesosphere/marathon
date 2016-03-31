package mesosphere.marathon.core.readiness

import mesosphere.marathon.core.task.Task

case class ReadinessCheckResult(name: String,
                                taskId: Task.Id,
                                ready: Boolean,
                                lastResponse: Option[HttpResponse])

case class HttpResponse(status: Int, contentType: String, body: String)
