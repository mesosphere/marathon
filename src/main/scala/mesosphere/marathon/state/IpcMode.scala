package mesosphere.marathon
package state

import org.apache.mesos.{Protos => mesos}

/**
  * Defines an IPC mode for linux tasks
  */
sealed trait IpcMode {
  val value: String

  def toProto: Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode
  def toMesos: mesos.LinuxInfo.IpcMode
}

object IpcMode {

  case object Private extends IpcMode {
    override val value = raml.IPCMode.Private.value
    override val toProto = Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.PRIVATE
    override val toMesos = mesos.LinuxInfo.IpcMode.PRIVATE
  }
  case object ShareParent extends IpcMode {
    override val value = raml.IPCMode.ShareParent.value
    override val toProto = Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.SHARE_PARENT
    override val toMesos = mesos.LinuxInfo.IpcMode.SHARE_PARENT
  }

  private[this] val proto2Model: Map[Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode, IpcMode] = Map(
    Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.PRIVATE -> Private,
    Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.SHARE_PARENT -> ShareParent
  )

  private[this] val mesos2Model: Map[mesos.LinuxInfo.IpcMode, IpcMode] = Map(
    mesos.LinuxInfo.IpcMode.PRIVATE -> Private,
    mesos.LinuxInfo.IpcMode.SHARE_PARENT -> ShareParent
  )

  def fromProto(proto: Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode): IpcMode = proto2Model(proto)
  def fromMesos(proto: mesos.LinuxInfo.IpcMode): IpcMode = mesos2Model(proto)

}
