package mesosphere.marathon
package state

/**
  * Defines a kill selection for tasks. See [[mesosphere.marathon.core.deployment.ScalingProposition]].
  */
sealed trait KillSelection {
  def apply(a: Timestamp, b: Timestamp): Boolean = this match {
    case KillSelection.YoungestFirst => a.youngerThan(b)
    case KillSelection.OldestFirst => a.olderThan(b)
  }
  val value: String

  def toProto: Protos.KillSelection
}

object KillSelection {
  def withName(value: String): KillSelection = {
    if (value == "YOUNGEST_FIRST") YoungestFirst
    else if (value == "OLDEST_FIRST") OldestFirst
    else throw new NoSuchElementException(s"There is no KillSelection with name '$value'")
  }

  case object YoungestFirst extends KillSelection {
    override val value = "YOUNGEST_FIRST"
    override def toProto: Protos.KillSelection =
      Protos.KillSelection.YoungestFirst
  }
  case object OldestFirst extends KillSelection {
    override val value = "OLDEST_FIRST"
    override def toProto: Protos.KillSelection =
      Protos.KillSelection.OldestFirst
  }

  val DefaultKillSelection: KillSelection = YoungestFirst

  def fromProto(proto: Protos.KillSelection): KillSelection = {
    proto match {
      case Protos.KillSelection.YoungestFirst =>
        YoungestFirst
      case Protos.KillSelection.OldestFirst =>
        OldestFirst
    }
  }
}
