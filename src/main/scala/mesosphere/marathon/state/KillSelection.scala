package mesosphere.marathon.state

/**
  * Defines a kill selection for tasks. See [[mesosphere.marathon.upgrade.ScalingProposition]].
  */
sealed trait KillSelection {
  def apply(a: Timestamp, b: Timestamp): Boolean = this match {
    case KillSelection.YoungestFirst => a.youngerThan(b)
    case KillSelection.OldestFirst => a.olderThan(b)
  }
  val value: String
}

object KillSelection {
  def withName(value: String): KillSelection = {
    if (value == "YoungestFirst") YoungestFirst
    else if (value == "OldestFirst") OldestFirst
    else throw new NoSuchElementException(s"There is no KillSelection with name '$value'")
  }

  case object YoungestFirst extends KillSelection {
    override val value = "YoungestFirst"
  }
  case object OldestFirst extends KillSelection {
    override val value = "OldestFirst"
  }

  val DefaultKillSelection: KillSelection = YoungestFirst
}
