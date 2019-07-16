package mesosphere.marathon
package raml

/**
  * Conversion from [[state.IpcMode]] to [[raml.IPCMode]] and vice versa.
  */
trait IpcModeConversion {

  implicit val ramlIpcModeRead = Reads[raml.IPCMode, state.IpcMode] {
    case IPCMode.Private => state.IpcMode.Private
    case IPCMode.ShareParent => state.IpcMode.ShareParent
  }

  implicit val ramlIpcModeWrite = Writes[state.IpcMode, raml.IPCMode] {
    case state.IpcMode.Private => IPCMode.Private
    case state.IpcMode.ShareParent => IPCMode.ShareParent
  }

  implicit val protoRamlIpcMode = Writes[Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode, raml.IPCMode] {
    case Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.PRIVATE => IPCMode.Private
    case Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo.IpcMode.SHARE_PARENT => IPCMode.ShareParent
    case badValue => throw new IllegalStateException(s"unsupported ipcMode $badValue")
  }
}

object IpcModeConversion extends IpcModeConversion
