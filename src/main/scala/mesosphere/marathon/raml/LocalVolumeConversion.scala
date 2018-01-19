package mesosphere.marathon
package raml

import mesosphere.marathon.core.instance

object LocalVolumeConversion {
  implicit val localVolumeIdWrites: Writes[instance.LocalVolumeId, LocalVolumeId] = Writes { localVolumeId =>
    LocalVolumeId(
      runSpecId = localVolumeId.runSpecId.toRaml,
      containerPath = localVolumeId.name,
      uuid = localVolumeId.uuid,
      persistenceId = localVolumeId.idString
    )
  }

}
