package mesosphere.marathon
package core.instance

import mesosphere.marathon.state.PathId

case class LocalVolumeId(runSpecId: PathId, name: String, uuid: String) {
  import LocalVolumeId.delimiter
  lazy val idString: String = runSpecId.safePath + delimiter + name + delimiter + uuid

  override def toString: String = s"LocalVolume [$idString]"
}

