package mesosphere.marathon
package raml

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.Task

object EnrichedTaskConversion extends HealthConversion {

  implicit val enrichedTaskRamlWriter: Writes[core.appinfo.EnrichedTask, EnrichedTask] = Writes { enrichedTask =>
    val task: Task = enrichedTask.task

    val (startedAt, stagedAt, ports, version) =
      if (task.isActive) {
        (task.status.startedAt, Some(task.status.stagedAt), task.status.networkInfo.hostPorts, Some(task.runSpecVersion))
      } else {
        (None, None, Nil, None)
      }

    EnrichedTask(
      appId = enrichedTask.appId.toRaml,
      healthCheckResults = enrichedTask.healthCheckResults.map(_.toRaml),
      host = enrichedTask.agentInfo.host,
      id = task.taskId.toString,
      ipAddresses = task.status.networkInfo.ipAddresses.map(_.toRaml),
      ports = ports,
      servicePorts = enrichedTask.servicePorts,
      slaveId = enrichedTask.agentInfo.agentId,
      state = Condition.toMesosTaskStateOrStaging(task.status.condition).toRaml,
      stagedAt = stagedAt.map(_.toRaml),
      startedAt = startedAt.map(_.toRaml),
      version = version.map(_.toRaml),
      localVolumes = Nil
    )
  }

  /*
  task.reservationWithVolumes.foreach { reservation =>
    fields.update("localVolumes", reservation.volumeIds)
  }
    */
}
