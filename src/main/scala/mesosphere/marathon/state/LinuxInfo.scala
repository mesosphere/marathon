package mesosphere.marathon
package state

/**
  * Defines the seccomp expectations for the instance this is associated with.   This will govern the secure computing mode.
  * Unlike Mesos, unconfined is not optional.  If seccomp is provided, then unconfined is either true or false.  The optionality is with seccomp itself.
  *
  * @param profileName The profile name which defines the security model this container will run under.  It is required that this profile be defined at the agent.
  * @param unconfined True is running under a profile, False if under a profile.
  */
case class Seccomp(profileName: Option[String], unconfined: Boolean)

/**
  * Defines the linux runtime the container will run under.   This could include things such as seccomp, linux capabilities,
  * and shared pid namespaces.
  * @param seccomp The seccomp mode to use, either unconfined or which profile.
  */
case class LinuxInfo(seccomp: Option[Seccomp])

object LinuxInfo {

  /*
  rules:  if seccomp not defined = valid
          if profile is empty == valid (regardless of unconfined)
          if profile is not empty unconfined must be false
   */
  def valid(linuxInfo: LinuxInfo): Boolean = {
    if(linuxInfo.seccomp.isEmpty || linuxInfo.seccomp.get.profileName.isEmpty) return true
    linuxInfo.seccomp.get.profileName.isDefined && !linuxInfo.seccomp.get.unconfined
  }
}