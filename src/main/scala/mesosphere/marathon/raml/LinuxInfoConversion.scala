package mesosphere.marathon
package raml

trait LinuxInfoConversion extends IpcModeConversion {

  implicit val linuxInfoReads: Reads[raml.LinuxInfo, state.LinuxInfo] = Reads { ramlLinuxInfo =>
    val seccomp = ramlLinuxInfo.seccomp.map { ramlSeccomp =>
      state.Seccomp(ramlSeccomp.profileName, ramlSeccomp.unconfined)
    }
    val ipcInfo = ramlLinuxInfo.ipcInfo.map { ramlIpcInfo =>
      state.IPCInfo(ramlIpcInfo.mode.fromRaml, ramlIpcInfo.shmSize)
    }
    state.LinuxInfo(seccomp, ipcInfo)
  }

  implicit val linuxInfoWrites: Writes[state.LinuxInfo, raml.LinuxInfo] = Writes { stateLinuxInfo =>
    val seccomp = stateLinuxInfo.seccomp.map { seccomp =>
      Seccomp(seccomp.profileName, seccomp.unconfined)
    }
    val ipcInfo = stateLinuxInfo.ipcInfo.map(stateIpcInfo => raml.IPCInfo(stateIpcInfo.ipcMode.toRaml, stateIpcInfo.shmSize))
    raml.LinuxInfo(seccomp, ipcInfo)
  }

  implicit val linuxInfoRamlWrites: Writes[Protos.ExtendedContainerInfo.LinuxInfo, raml.LinuxInfo] = Writes { linuxInfo =>
    raml.LinuxInfo(
      if (linuxInfo.hasSeccomp) Some(linuxInfo.getSeccomp.toRaml) else None,
      if (linuxInfo.hasIpcInfo) Some(linuxInfo.getIpcInfo.toRaml) else None
    )
  }

  implicit val seccompRamlWrites: Writes[Protos.ExtendedContainerInfo.LinuxInfo.Seccomp, raml.Seccomp] = Writes { seccomp =>
    val profileName = if (seccomp.hasProfileName) Some(seccomp.getProfileName) else None
    raml.Seccomp(profileName, seccomp.getUnconfined)
  }

  implicit val ipcInfoRamlWrites: Writes[Protos.ExtendedContainerInfo.LinuxInfo.IpcInfo, raml.IPCInfo] = Writes { ipcInfo =>
    val shmSize = if (ipcInfo.hasShmSize) Some(ipcInfo.getShmSize) else None
    raml.IPCInfo(ipcInfo.getIpcMode.toRaml, shmSize)
  }

}

object LinuxInfoConversion extends LinuxInfoConversion
