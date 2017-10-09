package mesosphere.marathon
package raml

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.task.Task

object EnrichedTaskConversion extends HealthConversion with DefaultConversions {

  implicit val localVolumeIdWrites: Writes[Task.LocalVolumeId, LocalVolumeId] = Writes { localVolumeId =>
    LocalVolumeId(
      runSpecId = localVolumeId.runSpecId.toRaml,
      containerPath = localVolumeId.containerPath,
      uuid = localVolumeId.uuid,
      persistenceId = localVolumeId.idString
    )
  }

  implicit val enrichedTaskRamlWrite: Writes[core.appinfo.EnrichedTask, EnrichedTask] = Writes { enrichedTask =>
    val task: Task = enrichedTask.task

    val (startedAt, stagedAt, ports, version) =
      if (task.isActive) {
        (task.status.startedAt, Some(task.status.stagedAt), task.status.networkInfo.hostPorts, Some(task.runSpecVersion))
      } else {
        (None, None, Nil, None)
      }

    val ipAddresses = task.status.networkInfo.ipAddresses.toRaml

    val localVolumes = task.reservationWithVolumes.fold(Seq.empty[LocalVolumeId]) { reservation =>
      reservation.volumeIds.toRaml
    }

    EnrichedTask(
      appId = enrichedTask.appId.toRaml,
      healthCheckResults = enrichedTask.healthCheckResults.toRaml,
      host = enrichedTask.agentInfo.host,
      id = task.taskId.idString,
      ipAddresses = ipAddresses,
      ports = ports,
      servicePorts = enrichedTask.servicePorts,
      slaveId = enrichedTask.agentInfo.agentId,
      state = Condition.toMesosTaskStateOrStaging(task.status.condition).toRaml,
      stagedAt = stagedAt.toRaml,
      startedAt = startedAt.toRaml,
      version = version.toRaml,
      localVolumes = localVolumes
    )
  }
}
