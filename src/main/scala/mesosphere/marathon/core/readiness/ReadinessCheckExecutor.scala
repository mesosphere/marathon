package mesosphere.marathon
package core.readiness

import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, PortAssignment, RunSpec }
import rx.lang.scala.Observable

import scala.concurrent.duration.FiniteDuration

/**
  * Poll readiness of the given endpoint until we receive readiness confirmation.
  * Intermediate results are returned as part of the Observable.
  *
  * Readiness checks are currently only available for AppDefinitions, therefore this code
  * is typed for [[mesosphere.marathon.state.RunSpec]]
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
      runSpec: RunSpec,
      task: Task): Seq[ReadinessCheckExecutor.ReadinessCheckSpec] = {

      require(task.runSpecId == runSpec.id, s"Task id and RunSpec id must match: ${task.runSpecId} != ${runSpec.id}")
      require(task.isActive, s"Unable to perform readiness checks against inactive ${task.taskId}")
      require(
        task.status.networkInfo.effectiveIpAddress(runSpec).isDefined,
        "Task is unreachable: an IP address was requested but not yet assigned")

      runSpec match {
        case app: AppDefinition =>
          app.readinessChecks.map { checkDef =>

            // determining the URL is difficult, everything else is just copying configuration
            val url = {
              val schema = checkDef.protocol match {
                case ReadinessCheck.Protocol.HTTP => "http"
                case ReadinessCheck.Protocol.HTTPS => "https"
              }

              val portAssignments: Seq[PortAssignment] = task.status.networkInfo.portAssignments(app, includeUnresolved = false)
              val effectivePortAssignment = portAssignments.find(_.portName.contains(checkDef.portName)).getOrElse(
                throw new IllegalArgumentException(s"no port definition for port name '${checkDef.portName}' was found"))

              val host = effectivePortAssignment.effectiveIpAddress.getOrElse(
                throw new IllegalArgumentException(s"no effective IP address for '${checkDef.portName}' was found"))

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

        // no support for pods readiness checks via marathon
        case _ =>
          Seq.empty
      }
    } // readinessCheckSpecsForTask
  } // ReadinessCheckSpec
}
