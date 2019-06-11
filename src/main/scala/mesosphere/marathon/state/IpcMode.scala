package mesosphere.marathon
package state

/**
  * Defines an IPC mode for linux tasks
  */
sealed trait IpcMode {
  val value: String

  def toProto: Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode
}

object IpcMode {

  case object Private extends IpcMode {
    override val value = raml.IPCMode.Private.value
    override val toProto = Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.PRIVATE
  }
  case object ShareParent extends IpcMode {
    override val value = raml.IPCMode.ShareParent.value
    override val toProto = Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.SHARE_PARENT
  }

  private[this] val proto2Model: Map[Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode, IpcMode] = Map(
    Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.PRIVATE -> Private,
    Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.SHARE_PARENT -> ShareParent
  )

  def fromProto(proto: Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode): IpcMode = proto2Model(proto)
}

