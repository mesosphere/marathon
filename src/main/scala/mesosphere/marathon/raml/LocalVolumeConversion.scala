package mesosphere.marathon
package raml

object LocalVolumeConversion {
  implicit val localVolumeIdWrites: Writes[core.instance.LocalVolumeId, LocalVolumeId] = Writes { localVolumeId =>
    LocalVolumeId(
      runSpecId = localVolumeId.runSpecId.toRaml,
      containerPath = localVolumeId.name,
      uuid = localVolumeId.uuid,
      persistenceId = localVolumeId.idString
    )
  }

}
