package mesosphere.marathon.state

/**
  * Convenient trait to implicitly translate between T to PathId
  * @tparam T the type to convert from.
  */
trait ToPathId[T] {
  def apply(t: T): PathId
}

object ToPathId {
  implicit val appId: ToPathId[AppDefinition] = new ToPathId[AppDefinition] {
    override def apply(app: AppDefinition): PathId = app.id
  }
  implicit val groupId: ToPathId[Group] = new ToPathId[Group] {
    override def apply(group: Group): PathId = group.id
  }
}
