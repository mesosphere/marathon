package mesosphere.mesos

import org.apache.mesos

import scala.util.Try

object VolumeProfileMatcher {

  /**
    * @param requiredProfileName the name of the profile that needs to be set on a disk resource, if any.
    * @param resource the resource that shall be matched against the provided requiredProfileName
    * @return true, if the provided resource is a disk and has the required profile name,
    * or if no profile is requested and the disk has no profile set.
    * Will return false if the profile does not match or the disk has a profile
    * while no profile is requested.
    */
  def matchesProfileName(requiredProfileName: Option[String], resource: mesos.Protos.Resource): Boolean = {
    val diskProfile: Option[String] = Try(resource.getDisk.getSource.getProfile).filter(_.nonEmpty).toOption

    requiredProfileName.map { profile =>
      // If a profile is specified, only match disk resources that have that specified profile
      diskProfile.contains(profile)
    }.getOrElse {
      // If no profile is specified, only match disks that do not have a profile set at all
      diskProfile.isEmpty
    }
  }
}
