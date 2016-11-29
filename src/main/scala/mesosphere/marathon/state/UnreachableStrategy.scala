package mesosphere.marathon.state

import mesosphere.marathon.state.UnreachableStrategy.KillSelection

import scala.concurrent.duration._

/**
  * Defines the time outs and kill strategy for unreachable tasks.
  */
case class UnreachableStrategy(
  timeUntilInactive: FiniteDuration = UnreachableStrategy.DefaultTimeUntilInactive,
  timeUntilExpunge: FiniteDuration = UnreachableStrategy.DefaultTimeUntilExpunge,
  killSelection: KillSelection = UnreachableStrategy.DefaultKillSelection)

object UnreachableStrategy {
  val DefaultTimeUntilInactive = 3.minutes
  val DefaultTimeUntilExpunge = 6.minutes
  val DefaultKillSelection = KillSelection.YoungestFirst

  sealed trait KillSelection {
    def apply(a: Timestamp, b: Timestamp): Boolean = this match {
      case KillSelection.YoungestFirst => a.youngerThan(b)
      case KillSelection.OldestFirst => a.olderThan(b)
    }
  }

  object KillSelection {
    def withName(value: String): KillSelection = {
      if (value == "YoungestFirst") YoungestFirst
      else if (value == "OldestFirst") OldestFirst
      else throw new NoSuchElementException(s"There is no KillSelection with name '$value'")
    }

    case object YoungestFirst extends KillSelection
    case object OldestFirst extends KillSelection
  }
}
