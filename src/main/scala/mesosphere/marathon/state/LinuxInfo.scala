package mesosphere.marathon
package state

import com.wix.accord._

/**
  * Defines the seccomp expectations for the instance this is associated with.   This will govern the secure computing mode.
  * Unlike Mesos, unconfined is not optional.  If seccomp is provided, then unconfined is either true or false.  The optionality is with seccomp itself.
  *
  * @param profileName The profile name which defines the security model this container will run under.  It is required that this profile be defined at the agent.
  * @param unconfined  True is not running under a profile, False if running under a profile.
  */
case class Seccomp(profileName: Option[String], unconfined: Boolean)

/**
  * Defines the linux runtime the container will run under.   This could include things such as seccomp, linux capabilities,
  * and shared pid namespaces.
  *
  * @param seccomp The seccomp mode to use, either unconfined or which profile.
  */
case class LinuxInfo(seccomp: Option[Seccomp])

object LinuxInfo {

  /*
  rules:  if seccomp not defined = valid
          if profile is empty unconfined must be true to be valid
          if profile is not empty unconfined must be false to be valid
   */
  val validLinuxInfo =
    new Validator[LinuxInfo] {
      override def apply(linuxInfo: LinuxInfo): Result = {
        linuxInfo.seccomp match {
          case Some(seccomp) =>
            if (seccomp.profileName.isDefined && seccomp.unconfined) Failure(Set(RuleViolation(linuxInfo, "Seccomp unconfined can NOT be true when Profile is defined")))
            else if (seccomp.profileName.isEmpty && !seccomp.unconfined) Failure(Set(RuleViolation(linuxInfo, "Seccomp unconfined must be true when Profile is NOT defined")))
            else Success // <-- otherwise it is not complete
          case None => Success
        }
      }
    }
}