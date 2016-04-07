package mesosphere.marathon.core.readiness

import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import rx.lang.scala.Observable

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

/**
  * Poll readiness of the given endpoint until we receive readiness confirmation.
  * Intermediate results are returned as part of the Observable.
  */
trait ReadinessCheckExecutor {
  def execute(readinessCheckInfo: ReadinessCheckSpec): Observable[ReadinessCheckResult]
}

object ReadinessCheckExecutor {
  /**
    * A self-contained description which contains all information necessary to perform
    * a readiness check.
    */
  case class ReadinessCheckSpec(
    taskId: Task.Id,
    checkName: String,
    url: String,
    interval: FiniteDuration = ReadinessCheck.DefaultInterval,
    timeout: FiniteDuration = ReadinessCheck.DefaultTimeout,
    httpStatusCodesForReady: Set[Int] = ReadinessCheck.DefaultHttpStatusCodesForReady,
    preserveLastResponse: Boolean = ReadinessCheck.DefaultPreserveLastResponse)

  object ReadinessCheckSpec {
    /**
      * Returns the readiness checks for the given task.
      */
    def readinessCheckSpecsForTask(
      app: AppDefinition,
      task: Task,
      launched: Task.Launched): Seq[ReadinessCheckExecutor.ReadinessCheckSpec] = {

      require(task.appId == app.id, s"Task appId and AppDefinition appId must match: ${task.appId} != ${app.id}")
      require(task.launched == Some(launched), "Launched info is not the one contained in the task")
      require(task.effectiveIpAddress(app).isDefined,
        "Task is unreachable: an IP address was requested but not yet assigned")

      app.readinessChecks.map { checkDef =>

        // determining the URL is difficult, everything else is just copying configuration
        val url = {
          val schema = checkDef.protocol match {
            case ReadinessCheck.Protocol.HTTP  => "http"
            case ReadinessCheck.Protocol.HTTPS => "https"
          }

          val portAssignmentsByName = app.portAssignments(task).getOrElse(
            throw new IllegalStateException(s"no ports assignments for AppDefinition: [$app] - Task: [$task]")
          ).map(portAssignment => portAssignment.portName -> portAssignment).toMap

          val effectivePortAssignment = portAssignmentsByName.getOrElse(
            Some(checkDef.portName),
            throw new IllegalArgumentException(s"no port definition for port name '${checkDef.portName}' was found")
          )

          val host = effectivePortAssignment.effectiveIpAddress
          val port = effectivePortAssignment.effectivePort

          s"$schema://$host:$port${checkDef.path}"
        }

        ReadinessCheckSpec(
          taskId = task.taskId,
          checkName = checkDef.name,
          url = url,
          interval = checkDef.interval,
          timeout = checkDef.timeout,
          httpStatusCodesForReady = checkDef.httpStatusCodesForReady,
          preserveLastResponse = checkDef.preserveLastResponse
        )
      }
    }
  }
}
