package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._

/**
  * Defines the seccomp expectations for the instance this is associated with.   This will govern the secure computing mode.
  * Unlike Mesos, unconfined is not optional.  If seccomp is provided, then unconfined is either true or false.  The optionality is with seccomp itself.
  *
  * @param profileName The profile name which defines the security model this container will run under.  It is required that this profile be defined at the agent.
  * @param unconfined  True is not running under a profile, False if running under a profile.
  */
case class Seccomp(profileName: Option[String], unconfined: Boolean)

/**
  * Defines the IPC configuration for the instance or executor this is associated with.
  *
  * @param ipcMode The IPC mode configured
  * @param shmSize The shared memory size in MB when using private IPC mode
  */
case class IPCInfo(ipcMode: state.IpcMode, shmSize: Option[Int])

/**
  * Defines the linux runtime the container will run under. This could include things such as seccomp, linux capabilities,
  * and shared pid namespaces.
  *
  * @param seccomp The seccomp mode to use, either unconfined or which profile.
  * @param ipcInfo The IPC configuration to use
  */
case class LinuxInfo(seccomp: Option[Seccomp], ipcInfo: Option[IPCInfo])

object LinuxInfo {

  //  rules:  if seccomp not defined = valid
  //          if profile is empty unconfined must be true to be valid
  //          if profile is not empty unconfined must be false to be valid
  //          AND
  //          if sharedMem not defined = valid
  //          if ipcMode share_parent, shmSize must be undefined to be valid

  private[this] implicit val validIpcInfoState: Validator[Option[state.IPCInfo]] = new Validator[Option[state.IPCInfo]] {
    override def apply(ipcInfoOpt: Option[state.IPCInfo]): Result = {
      ipcInfoOpt match {
        case Some(ipcInfo) =>
          if (ipcInfo.ipcMode == state.IpcMode.ShareParent && ipcInfo.shmSize.isDefined)
            Failure(Set(RuleViolation(ipcInfo, "ipcInfo shmSize can NOT be set when mode is SHARE_PARENT")))
          else Success
        case None => Success
      }
    }
  }

  private[this] implicit val validIpcInfoRaml: Validator[Option[raml.IPCInfo]] = new Validator[Option[raml.IPCInfo]] {
    override def apply(ipcInfoOpt: Option[raml.IPCInfo]): Result = {
      ipcInfoOpt match {
        case Some(ipcInfo) =>
          if (ipcInfo.mode == raml.IPCMode.ShareParent && ipcInfo.shmSize.isDefined)
            Failure(Set(RuleViolation(ipcInfo, "ipcInfo shmSize can NOT be set when mode is SHARE_PARENT")))
          else Success
        case None => Success
      }
    }
  }

  private[this] val validSeccompForContainerState = new Validator[Option[state.Seccomp]] {
    override def apply(seccompOpt: Option[state.Seccomp]): Result = {
      seccompOpt match {
        case Some(seccomp) =>
          if (seccomp.profileName.isDefined && seccomp.unconfined)
            Failure(Set(RuleViolation(seccomp, "Seccomp unconfined can NOT be true when Profile is defined")))
          else if (seccomp.profileName.isEmpty && !seccomp.unconfined)
            Failure(Set(RuleViolation(seccomp, "Seccomp unconfined must be true when Profile is NOT defined")))
          else Success
        case None => Success
      }
    }
  }

  private[this] val validSeccompForContainerRaml = new Validator[Option[raml.Seccomp]] {
    override def apply(seccompOpt: Option[raml.Seccomp]): Result = {
      seccompOpt match {
        case Some(seccomp) =>
          if (seccomp.profileName.isDefined && seccomp.unconfined)
            Failure(Set(RuleViolation(seccomp, "Seccomp unconfined can NOT be true when Profile is defined")))
          else if (seccomp.profileName.isEmpty && !seccomp.unconfined)
            Failure(Set(RuleViolation(seccomp, "Seccomp unconfined must be true when Profile is NOT defined")))
          else Success
        case None => Success
      }
    }
  }

  private[this] val validSeccompForPodsRaml = new Validator[Option[raml.Seccomp]] {
    override def apply(seccompOpt: Option[raml.Seccomp]): Result = {
      seccompOpt match {
        case Some(seccomp) => Failure(Set(RuleViolation(seccomp, "Seccomp configuration on executor level is not supported")))
        case None => Success
      }
    }
  }

  /*
   * For containers we allow full seccomp and ipcInfo configuration
   */
  val validLinuxInfoForContainerState: Validator[state.LinuxInfo] = validator[state.LinuxInfo] { linuxInfo =>
    linuxInfo.seccomp is valid(validSeccompForContainerState)
    linuxInfo.ipcInfo is valid
  }
  val validLinuxInfoForContainerRaml: Validator[raml.LinuxInfo] = validator[raml.LinuxInfo] { linuxInfo =>
    linuxInfo.seccomp is valid(validSeccompForContainerRaml)
    linuxInfo.ipcInfo is valid
  }

  /*
   * For pods/executors we only allow ipcInfo to be set, defining seccomp here is not supported (yet)
   */
  val validLinuxInfoForPodRaml: Validator[raml.LinuxInfo] = validator[raml.LinuxInfo] { linuxInfo =>
    linuxInfo.seccomp is valid(validSeccompForPodsRaml)
    linuxInfo.ipcInfo is valid
  }

}
