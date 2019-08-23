package mesosphere.marathon
package raml

import mesosphere.marathon.core.condition
import mesosphere.marathon.raml.LocalVolumeConversion.localVolumeIdWrites

object TaskConversion extends HealthConversion with DefaultConversions {
  implicit val enrichedTaskRamlWrite: Writes[core.appinfo.EnrichedTask, Task] = Writes { enrichedTask =>
    val task: core.task.Task = enrichedTask.task

    val (startedAt, stagedAt, ports, version) =
      if (task.isActive) {
        (task.status.startedAt, Some(task.status.stagedAt), task.status.networkInfo.hostPorts, Some(task.runSpecVersion))
      } else {
        (None, None, Nil, None)
      }

    val ipAddresses = task.status.networkInfo.ipAddresses.toRaml

    val localVolumes = enrichedTask.reservation.fold(Seq.empty[LocalVolumeId]) { reservation =>
      reservation.volumeIds.toRaml
    }

    val checkStatus: Option[CheckStatus] = task.status.mesosStatus.collect {
      case mesosStatus if mesosStatus.hasCheckStatus => mesosStatus.getCheckStatus
    }.map { check =>
      check.toRaml
    }.filter { ramlCheck =>
      ramlCheck.command.nonEmpty || ramlCheck.tcp.nonEmpty || ramlCheck.http.nonEmpty
    }

    Task(
      appId = enrichedTask.appId.toRaml,
      healthCheckResults = enrichedTask.healthCheckResults.toRaml,
      checkResult = checkStatus,
      host = enrichedTask.agentInfo.host,
      id = task.taskId.idString,
      ipAddresses = ipAddresses,
      ports = ports,
      servicePorts = enrichedTask.servicePorts,
      slaveId = enrichedTask.agentInfo.agentId,
      state = condition.Condition.toMesosTaskStateOrStaging(task.status.condition).toRaml,
      stagedAt = stagedAt.toRaml,
      startedAt = startedAt.toRaml,
      version = version.toRaml,
      localVolumes = localVolumes,
      region = enrichedTask.agentInfo.region,
      zone = enrichedTask.agentInfo.zone,
      role = enrichedTask.role
    )
  }

  implicit val taskCountsWrite: Writes[core.appinfo.TaskCounts, raml.TaskCounts] = Writes { taskCounts =>
    raml.TaskCounts(taskCounts.tasksStaged, taskCounts.tasksRunning, taskCounts.tasksHealthy, taskCounts.tasksUnhealthy)
  }
}
