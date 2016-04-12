package mesosphere.marathon.state

/**
  * Convenient trait to implicitly translate between T to an Identity of T
  *
  * @tparam T the type to convert from.
  */
trait ToIdentity[T] {
  def apply(t: T): String
}

object ToIdentity {
  implicit val appId: ToIdentity[AppDefinition] = new ToIdentity[AppDefinition] {
    override def apply(app: AppDefinition): String = app.id.relativePath
  }
  implicit val groupId: ToIdentity[Group] = new ToIdentity[Group] {
    override def apply(group: Group): String = group.id.relativePath
  }
  implicit val externalVolumeIdentity: ToIdentity[ExternalVolume] = new ToIdentity[ExternalVolume] {
    override def apply(volume: ExternalVolume): String = volume.containerPath
  }
  implicit val volumeIdentity: ToIdentity[Volume] = new ToIdentity[Volume] {
    override def apply(volume: Volume): String = volume.containerPath
  }
}
