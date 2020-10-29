package mesosphere.marathon
package state

sealed trait VersionInfo {
  def version: Timestamp
  def lastConfigChangeVersion: Timestamp

  def withScaleChange(newVersion: Timestamp): VersionInfo = {
    VersionInfo.forNewConfig(version).withScaleChange(newVersion)
  }

  def withRestartChange(newVersion: Timestamp): VersionInfo = {
    VersionInfo.forNewConfig(version).withRestartChange(newVersion)
  }

  def withConfigChange(newVersion: Timestamp): VersionInfo = {
    VersionInfo.forNewConfig(newVersion)
  }
}

object VersionInfo {

  /**
    * This should only be used for new [[mesosphere.marathon.state.RunSpec]]s.
    *
    * If you set the versionInfo of existing Specs to `NoVersion`,
    * it will result in a restart when this Spec is passed to the GroupManager update method.
    */
  case object NoVersion extends VersionInfo {
    override def version: Timestamp = Timestamp(0)
    override def lastConfigChangeVersion: Timestamp = version
  }

  /**
    * Only contains a version timestamp. Will be converted to a FullVersionInfo before stored.
    */
  case class OnlyVersion(version: Timestamp) extends VersionInfo {
    override def lastConfigChangeVersion: Timestamp = version
  }

  /**
    * @param version The version timestamp
    * @param lastScalingAt The timestamp of the last scaling change
    * @param lastConfigChangeAt The timestamp of the restart change
    */
  case class FullVersionInfo(version: Timestamp, lastScalingAt: Timestamp, lastConfigChangeAt: Timestamp) extends VersionInfo {

    override def lastConfigChangeVersion: Timestamp = lastConfigChangeAt

    override def withScaleChange(newVersion: Timestamp): VersionInfo = {
      copy(version = newVersion, lastScalingAt = newVersion)
    }

    override def withRestartChange(newVersion: Timestamp): VersionInfo = {
      copy(version = newVersion, lastConfigChangeAt = newVersion)
    }
  }

  def forNewConfig(newVersion: Timestamp): FullVersionInfo =
    FullVersionInfo(
      version = newVersion,
      lastScalingAt = newVersion,
      lastConfigChangeAt = newVersion
    )
}
