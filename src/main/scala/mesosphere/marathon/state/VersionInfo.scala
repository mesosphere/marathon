package mesosphere.marathon.state

sealed trait VersionInfo {
  def version: Timestamp
  def lastConfigChangeVersion: Timestamp

  def withScaleOrRestartChange(newVersion: Timestamp): VersionInfo = {
    VersionInfo.forNewConfig(version).withScaleOrRestartChange(newVersion)
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
    * @param version The version timestamp (we are currently assuming that this is the same as lastChangeAt)
    * @param lastScalingAt The time stamp of the last change including scaling or restart changes
    * @param lastConfigChangeAt The time stamp of the last change that changed configuration
    *                           besides scaling or restarting
    */
  case class FullVersionInfo(
      version: Timestamp,
      lastScalingAt: Timestamp,
      lastConfigChangeAt: Timestamp) extends VersionInfo {

    override def lastConfigChangeVersion: Timestamp = lastConfigChangeAt

    override def withScaleOrRestartChange(newVersion: Timestamp): VersionInfo = {
      copy(version = newVersion, lastScalingAt = newVersion)
    }
  }

  def forNewConfig(newVersion: Timestamp): FullVersionInfo = FullVersionInfo(
    version = newVersion,
    lastScalingAt = newVersion,
    lastConfigChangeAt = newVersion
  )
}
